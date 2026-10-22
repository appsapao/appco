package com.yundac.apkcloner.core.axml

import com.yundac.apkcloner.core.clone.PackageNames

/**
 * Raised when a manifest cannot be patched safely. Cloning stops here rather
 * than producing an APK that installs but misbehaves at runtime.
 */
class ManifestEditorException(message: String) : Exception(message)

/** One `<permission>` declaration and everything needed to remap it consistently. */
data class PermissionDeclaration(
    /** The element carrying the declaration, so edits can target it precisely. */
    val element: AxmlElement,
    /** The fully-qualified declared name, e.g. `com.example.app.permission.C2D_MESSAGE`. */
    val name: String,
    /** The protection level literal (signature/normal/dangerous), or null when absent. */
    val protectionLevel: String?,
    /** Every `uses-permission` / `uses-permission-sdk-23` element referencing [name]. */
    val users: List<AxmlElement>,
) {
    /** The `.permission.` fragment used to derive the clone-owned replacement name. */
    val permissionFragment: String?
        get() = name.indexOf(".permission.").takeIf { it >= 0 }?.let { name.substring(it) }
}

/**
 * High-level manifest edits needed to turn one installed APK into a clone.
 *
 * Everything here operates on [AxmlDocument] and is byte-exact on re-encode:
 * the only strings that change are the ones an edit targets, so class names in
 * `android:name` keep pointing at the DEX classes of the original package.
 *
 * String-pool entries are shared by every attribute that references them, so no
 * edit ever rewrites an entry in place: replacements are allocated as new pool
 * entries and only the intended attributes (plus their raw-value slots) are
 * repointed. See StringPoolEdit.kt.
 */
class ManifestEditor(val document: AxmlDocument) {

    fun manifestElement(): AxmlElement = document.root

    fun applicationElement(): AxmlElement? = document.root.childElements("application").firstOrNull()

    /** The `package` attribute of the `<manifest>` element. */
    fun packageAttribute(): AxmlAttribute? =
        document.root.attributes.firstOrNull { it.name == "package" && it.namespace == null }

    fun packageName(): String? = document.stringValue(packageAttribute())

    /**
     * Replaces the manifest package name.
     *
     * The old pool entry is *not* modified in place: other attributes may share
     * it (a provider authority equal to the package name, a label, ...). The
     * package attribute alone is repointed at a freshly allocated copy --
     * renaming unrelated attributes would corrupt them; provider authorities
     * are renamed explicitly by [renameProviderAuthorities] instead.
     */
    fun renamePackage(newPackage: String): String {
        val attribute = packageAttribute()
            ?: throw ManifestEditorException("Manifest has no package attribute")
        if (attribute.type != ValueType.STRING) {
            throw ManifestEditorException(
                "Manifest package attribute is not a string (type 0x${attribute.type.toString(16)})"
            )
        }
        val oldPackage = document.strings.getOrNull(attribute.data)
            ?: throw ManifestEditorException("Manifest package string index is out of range")
        if (oldPackage == newPackage) return oldPackage
        document.setAttributeString(attribute, newPackage)
        return oldPackage
    }

    // ------------------------------------------------------------------
    // Relative component names
    // ------------------------------------------------------------------

    /** Attributes whose value is a *class name*, relative names included. */
    private fun classBearingAttributes(): List<Pair<AxmlElement, AxmlAttribute>> {
        val application = applicationElement() ?: return emptyList()
        val result = mutableListOf<Pair<AxmlElement, AxmlAttribute>>()
        application.frameworkAttribute("name")?.let { result += application to it }
        for (tag in COMPONENT_TAGS) {
            application.childElements(tag).forEach { component ->
                component.frameworkAttribute("name")?.let { result += component to it }
                if (tag == "activity-alias") {
                    // Alias targets are class names too.
                    component.frameworkAttribute("targetActivity")?.let { result += component to it }
                }
                if (tag == "activity") {
                    // Parent activities (for up-navigation) are class names too.
                    component.frameworkAttribute("parentActivityName")?.let { result += component to it }
                }
            }
        }
        return result
    }

    /**
     * Rewrites every *relative* class-bearing name (values starting with `.`)
     * into an absolute name against [originalPackage].
     *
     * Android resolves a leading-dot class name against the manifest `package`
     * attribute; after [renamePackage] that would point inside the renamed
     * package, where the DEX declares no such class, and the component would
     * fail to instantiate. Normalizing before the rename keeps the classes of
     * the original package addressable. Covers the application class,
     * activities, services, receivers, providers, activity-alias targets and
     * parent activities.
     */
    fun normalizeRelativeClassNames(originalPackage: String = checkNotNull(packageName())): List<Pair<String, String>> {
        val changes = mutableListOf<Pair<String, String>>()
        for ((_, attribute) in classBearingAttributes()) {
            if (attribute.type != ValueType.STRING) continue
            val current = document.strings.getOrNull(attribute.data) ?: continue
            if (current.isEmpty() || !current.startsWith(".")) continue
            val absolute = originalPackage + current
            PackageNames.problem(absolute)?.let {
                throw ManifestEditorException("Relative manifest name '$current' resolves to an invalid class name: $it")
            }
            document.setAttributeString(attribute, absolute)
            changes.add(current to absolute)
        }
        return changes
    }

    // ------------------------------------------------------------------
    // Provider authorities
    // ------------------------------------------------------------------

    /** All `<provider>` elements, including providers nested in other elements. */
    fun providerElements(): List<AxmlElement> =
        document.root.descendants().filter { it.name == "provider" }.toList()

    /** Every individual authority currently declared anywhere in the manifest. */
    fun allProviderAuthorities(): List<String> =
        providerElements().mapNotNull { it.frameworkAttribute("authorities") }.flatMap { attribute ->
            document.strings.getOrNull(attribute.data)?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()
        }

    /**
     * Renames **every** provider authority by appending [suffix] as a new last
     * segment: `com.example.source.fileprovider` becomes
     * `com.example.source.fileprovider.clone1`, and authorities completely
     * unrelated to the package prefix (`mynotes.provider`) just as well.
     *
     * Two packages declaring the same authority cannot be installed side by
     * side, so this is required for installation, not cosmetics. Prefix-only
     * renaming silently left unrelated authorities colliding -- hence the
     * suffix form, which never depends on the authority sharing the package
     * prefix and always produces a valid authority name.
     *
     * Authority strings are frequently embedded in DEX code and resources
     * (`ContentResolver` calls, FileProvider metadata). Changing the manifest
     * alone cannot guarantee working provider access, so the pipeline reports
     * every renamed authority as a warning.
     */
    fun renameProviderAuthorities(suffix: String): List<Pair<String, String>> {
        val changes = LinkedHashMap<String, String>()
        for (element in providerElements()) {
            val attribute = element.frameworkAttribute("authorities") ?: continue
            if (attribute.type != ValueType.STRING) continue
            val current = document.strings.getOrNull(attribute.data) ?: continue
            // "android:authorities" is a ';'-separated list.
            val parts = current.split(';')
            var changed = false
            val renamed = parts.joinToString(";") { part ->
                val authority = part.trim()
                if (authority.isEmpty()) return@joinToString part
                val replacement = "$authority.$suffix"
                PackageNames.problem(replacement)?.let {
                    throw ManifestEditorException("Authority '$authority' cannot be renamed safely: $it")
                }
                changes[authority] = replacement
                changed = true
                replacement
            }
            if (changed) document.setAttributeString(attribute, renamed)
        }
        return changes.entries.map { it.key to it.value }
    }

    // ------------------------------------------------------------------
    // Custom permissions
    // ------------------------------------------------------------------

    /** Collects every `<permission>` declaration together with its users. */
    fun declaredPermissions(): List<PermissionDeclaration> {
        val declarations = document.root.childElements("permission") + document.root.childElements("permission-tree")
        val useElements = document.root.descendants().filter {
            it.name == "uses-permission" || it.name == "uses-permission-sdk-23"
        }.toList()
        return declarations.mapNotNull { declaration ->
            val nameAttribute = declaration.frameworkAttribute("name") ?: return@mapNotNull null
            val name = document.stringValue(nameAttribute) ?: return@mapNotNull null
            val users = useElements.filter { use ->
                document.stringValue(use.frameworkAttribute("name")) == name
            }
            PermissionDeclaration(
                element = declaration,
                name = name,
                protectionLevel = document.stringValue(declaration.frameworkAttribute("protectionLevel")),
                users = users,
            )
        }
    }

    /**
     * Detects custom-permission ownership conflicts **before** the clone is
     * built, and computes a consistent mapping for the permissions the clone
     * owns.
     *
     * Rules:
     *  - Clone-owned declarations (the name starts with the source package) are
     *    remapped consistently: the declaration and every matching
     *    `uses-permission` move to the new name together; the declared
     *    `android:protectionLevel` attribute is never touched, so protection
     *    levels are preserved verbatim.
     *  - [installedDeclaredPermissions] lists permissions declared by other
     *    already-installed packages; a collision there blocks the clone because
     *    Android would refuse the installation.
     *  - Anything that cannot be remapped safely blocks the clone: a name
     *    without a `.permission.` fragment (no stable derivation), a remapping
     *    that collides with another planned name, or a name that also appears
     *    embedded in unchanged manifest text -- the same string is almost
     *    certainly baked into the DEX/resources too, and renaming only the
     *    manifest would split the permission contract in two.
     *  - Permissions *not* owned by the source package stay untouched: they
     *    belong to another app, and claiming them in the clone would be worse
     *    than sharing them.
     */
    fun planPermissionRemapping(
        originalPackage: String,
        newPackage: String,
        installedDeclaredPermissions: Set<String> = emptySet(),
    ): List<StringRename> {
        val plans = mutableListOf<StringRename>()
        val plannedTargets = mutableSetOf<String>()
        for (declaration in declaredPermissions()) {
            val name = declaration.name
            if (!PackageNames.isOwnedBy(name, originalPackage)) continue
            val fragment = declaration.permissionFragment
                ?: throw ManifestEditorException(
                    "Custom permission '$name' is owned by '$originalPackage' but contains no " +
                        "'.permission.' fragment, so the clone cannot derive a safe replacement name."
                )
            val replacement = newPackage + fragment
            PackageNames.problem(replacement)?.let {
                throw ManifestEditorException("Custom permission '$name' cannot be remapped to '$replacement': $it")
            }
            if (replacement in installedDeclaredPermissions) {
                throw ManifestEditorException(
                    "Custom permission '$name' would need to become '$replacement', but that name is " +
                        "already declared by another installed package."
                )
            }
            if (replacement in plannedTargets) {
                throw ManifestEditorException("Two clone-owned permissions map to the same name '$replacement'.")
            }
            val externalReferences = countPermissionReferences(name, declaration)
            if (externalReferences > 0) {
                throw ManifestEditorException(
                    "Custom permission '$name' is referenced $externalReferences time(s) outside its own " +
                        "declaration and uses-permission elements. That text is also embedded in unchanged " +
                        "code/resources, so the permission cannot be remapped safely."
                )
            }
            plannedTargets += replacement
            plans += StringRename(from = name, to = replacement)
        }
        return plans
    }

    /** Applies a previously computed remapping; protection levels are untouched. */
    fun applyPermissionRemapping(renames: List<StringRename>): List<Pair<String, String>> {
        if (renames.isEmpty()) return emptyList()
        val byName = renames.associateBy { it.from }
        // Repoint exactly the owning attributes -- the `<permission>` name slot
        // and every matching `uses-permission` slot -- never the shared pool
        // entry itself, so unrelated attributes that happen to carry the same
        // string keep pointing at the original value.
        var changed = false
        for (declaration in declaredPermissions()) {
            val rename = byName[declaration.name] ?: continue
            document.setAttributeString(declaration.element.frameworkAttribute("name")!!, rename.to)
            for (user in declaration.users) {
                user.frameworkAttribute("name")?.let { document.setAttributeString(it, rename.to) }
            }
            changed = true
        }
        if (!changed) {
            throw ManifestEditorException(
                "Permission remapping aborted: none of ${renames.joinToString { "'${it.from}'" }} is " +
                    "declared in the manifest anymore."
            )
        }
        val missing = renames.filter { rename ->
            declaredPermissions().none { it.name == rename.to }
        }
        if (missing.isNotEmpty()) {
            throw ManifestEditorException(
                "Permission remapping aborted: ${missing.joinToString { "'${it.from}' -> '${it.to}'" }} " +
                    "did not land on a permission declaration."
            )
        }
        return renames.map { it.from to it.to }
    }

    /**
     * Counts references to [name] outside of [declaration]'s own name attribute
     * and the matching `uses-permission` elements. Anything else carrying the
     * exact string (meta-data values, intent categories, `android:permission`
     * attributes on components ...) is treated as embedded in unchanged
     * code/resources.
     */
    private fun countPermissionReferences(name: String, declaration: PermissionDeclaration): Int {
        var count = 0
        document.root.forEachDescendant { element ->
            val isDeclaration = element === declaration.element
            val isUseElement = element.name == "uses-permission" || element.name == "uses-permission-sdk-23"
            for (attribute in element.attributes) {
                if (isDeclaration && attribute.name == "name") continue
                if (isUseElement && attribute.name == "name") continue
                val value = if (attribute.type == ValueType.STRING) document.strings.getOrNull(attribute.data) else null
                if (value == name || attribute.rawValue == name) count++
            }
        }
        return count
    }

    /** Sets `android:label` on `<application>` to a literal string. */
    fun setApplicationLabel(label: String) {
        val application = applicationElement()
            ?: throw ManifestEditorException("Manifest has no <application> element")
        setStringAttribute(application, "label", AndroidAttr.LABEL, label)
    }

    /**
     * Sets the label of every launcher entry point (activities and activity
     * aliases that declare a MAIN/LAUNCHER filter).
     *
     * The label is what the launcher shows. Clones keep the original string
     * resources, so without this override a clone would appear under the
     * original app name.
     */
    fun setLauncherLabels(label: String): List<String> {
        val application = applicationElement() ?: return emptyList()
        val updated = mutableListOf<String>()
        application.children
            .filter { it.name == "activity" || it.name == "activity-alias" }
            .filter { hasLauncherIntent(it) }
            .forEach { component ->
                setStringAttribute(component, "label", AndroidAttr.LABEL, label)
                updated.add(document.stringValue(component.frameworkAttribute("name")) ?: "<unnamed>")
            }
        return updated
    }

    fun hasLauncherIntent(component: AxmlElement): Boolean {
        val launcherCategories = setOf(
            "android.intent.category.LAUNCHER",
            "android.intent.category.LEANBACK_LAUNCHER",
        )
        return component.childElements("intent-filter").any { filter ->
            val hasMain = filter.childElements("action").any {
                document.stringValue(it.frameworkAttribute("name")) == "android.intent.action.MAIN"
            }
            val hasLauncher = filter.childElements("category").any { category ->
                document.stringValue(category.frameworkAttribute("name")) in launcherCategories
            }
            hasMain && hasLauncher
        }
    }

    /** Removes `<uses-permission>` / `<uses-permission-sdk-23>` elements by name. */
    fun removePermissions(names: Set<String>): List<String> {
        if (names.isEmpty()) return emptyList()
        val removed = mutableListOf<String>()
        val iterator = document.root.children.iterator()
        while (iterator.hasNext()) {
            val child = iterator.next()
            if (child.name != "uses-permission" && child.name != "uses-permission-sdk-23") continue
            val name = document.stringValue(child.frameworkAttribute("name")) ?: continue
            if (name in names) {
                removed.add(name)
                iterator.remove()
            }
        }
        return removed
    }

    /** Sets a boolean framework attribute on `<application>`, adding it when absent. */
    fun setApplicationBoolean(name: String, resourceId: Int, value: Boolean) {
        val application = applicationElement()
            ?: throw ManifestEditorException("Manifest has no <application> element")
        val existing = application.frameworkAttribute(name)
        if (existing == null) {
            document.addString(name, resourceId)
            application.attributes.add(
                AxmlAttribute(
                    namespace = AndroidAttr.NAMESPACE,
                    name = name,
                    rawValue = if (value) "true" else "false",
                    type = ValueType.INT_BOOLEAN,
                    data = if (value) 0xFFFFFFFF.toInt() else 0,
                )
            )
        } else {
            existing.type = ValueType.INT_BOOLEAN
            existing.data = if (value) 0xFFFFFFFF.toInt() else 0
            existing.rawValue = if (value) "true" else "false"
        }
    }

    private fun setStringAttribute(element: AxmlElement, name: String, resourceId: Int, value: String) {
        val index = document.addString(value)
        // Register the attribute *name* with its framework resource ID so that a
        // newly added attribute is recognised by the platform parser.
        document.addString(name, resourceId)
        val existing = element.frameworkAttribute(name)
        if (existing != null) {
            existing.type = ValueType.STRING
            existing.data = index
            existing.rawValue = value
        } else {
            element.attributes.add(
                AxmlAttribute(
                    namespace = AndroidAttr.NAMESPACE,
                    name = name,
                    rawValue = value,
                    type = ValueType.STRING,
                    data = index,
                )
            )
        }
    }

    fun toBytes(): ByteArray = AxmlCodec.encode(document)

    companion object {
        private val COMPONENT_TAGS = setOf("activity", "activity-alias", "service", "receiver", "provider")
        fun parse(bytes: ByteArray): ManifestEditor = ManifestEditor(AxmlCodec.parse(bytes))
    }
}

internal fun AxmlElement.forEachDescendant(action: (AxmlElement) -> Unit) {
    action(this)
    children.forEach { it.forEachDescendant(action) }
}
