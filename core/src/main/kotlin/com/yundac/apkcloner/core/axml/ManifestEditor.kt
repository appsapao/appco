package com.yundac.apkcloner.core.axml

class ManifestEditorException(message: String) : Exception(message)

/**
 * High-level manifest edits needed to turn one installed APK into a clone.
 *
 * Everything here operates on [AxmlDocument] and is byte-exact on re-encode:
 * the only strings that change are the ones an edit targets, so class names in
 * `android:name` keep pointing at the DEX classes of the original package.
 */
class ManifestEditor(val document: AxmlDocument) {

    fun manifestElement(): AxmlElement = document.root

    fun applicationElement(): AxmlElement? = document.root.childElements("application").firstOrNull()

    /** The `package` attribute of the `<manifest>` element. */
    fun packageAttribute(): AxmlAttribute? =
        document.root.attributes.firstOrNull { it.name == "package" && it.namespace == null }

    fun packageName(): String? = document.stringValue(packageAttribute())

    /**
     * Replaces the manifest package name in place.
     *
     * The package string is rewritten rather than repointed so that provider
     * authorities that reused the very same pool entry are renamed too.
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
        document.strings.replace(attribute.data, newPackage)
        attribute.rawValue = newPackage
        return oldPackage
    }

    /**
     * Renames provider authorities that begin with [oldPrefix].
     *
     * This is required for installation, not cosmetics: two packages declaring the
     * same provider authority cannot be installed side by side.
     */
    fun renameProviderAuthorities(oldPrefix: String, newPrefix: String): List<Pair<String, String>> {
        val changes = mutableListOf<Pair<String, String>>()
        val application = applicationElement() ?: return changes
        application.childElements("provider").forEach { provider ->
            val attribute = provider.frameworkAttribute("authorities") ?: return@forEach
            if (attribute.type != ValueType.STRING) return@forEach
            val index = attribute.data
            val current = document.strings.getOrNull(index) ?: return@forEach
            // "android:authorities" is a ';'-separated list.
            val parts = current.split(';')
            val renamed = parts.joinToString(";") { part ->
                if (part.startsWith(oldPrefix)) {
                    val replacement = newPrefix + part.substring(oldPrefix.length)
                    changes.add(part to replacement)
                    replacement
                } else {
                    part
                }
            }
            if (renamed != current) {
                document.strings.replace(index, renamed)
                attribute.rawValue = renamed
            }
        }
        return changes
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
        fun parse(bytes: ByteArray): ManifestEditor = ManifestEditor(AxmlCodec.parse(bytes))
    }
}
