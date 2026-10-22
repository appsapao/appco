package com.yundac.apkcloner.core.axml

/**
 * Safe, attribute-targeted edits of the AXML string pool.
 *
 * Pool entries are shared: several attributes (and a matching raw-value slot)
 * can point at the same index. Replacing an entry in place therefore rewrites
 * every attribute that references it -- which is exactly what must not happen
 * when renaming one specific value. These helpers instead allocate a fresh
 * entry for the replacement text and repoint only the targeted attributes
 * (plus their raw-value slots).
 */

/** One logical rename applied across the document: every reference to [from]. */
data class StringRename(val from: String, val to: String)

/**
 * Renames every reference to a `from` value that is **not** one of the
 * permission declaration/`uses-permission` name attributes listed in
 * [keepTargeted]: attribute values, attribute raw values, element names and
 * namespace strings. Returns the renames that actually had at least one
 * global reference. Existing pool entries are never modified in place;
 * replacement strings are appended and only the referencing slots are
 * repointed.
 *
 * This is the conservative global form used for provider authorities: leaving a
 * single stale reference behind would break installation (duplicate authority),
 * so every reference must move together. Permission remapping deliberately
 * does *not* use this path -- there the exact owning attributes are repointed
 * individually, because a permission string may legitimately be shared with
 * unrelated text (e.g. an activity class named like the permission).
 */
fun AxmlDocument.renameAllStringReferences(
    renames: List<StringRename>,
    keepTargeted: Set<AxmlAttribute> = emptySet(),
): List<StringRename> {
    if (renames.isEmpty()) return emptyList()
    val replacements = HashMap<String, Int>()
    fun replacementFor(value: String): Int = replacements.getOrPut(value) { addString(value) }

    var applied = false
    forEachAttribute { attribute ->
        if (attribute in keepTargeted) return@forEachAttribute
        if (attribute.type == ValueType.STRING && strings.getOrNull(attribute.data) != null) {
            val current = strings[attribute.data]
            val target = renames.firstOrNull { it.from == current } ?: return@forEachAttribute
            attribute.data = replacementFor(target.to)
            attribute.rawValue = target.to
            applied = true
        } else if (attribute.rawValue != null) {
            val target = renames.firstOrNull { it.from == attribute.rawValue } ?: return@forEachAttribute
            attribute.rawValue = target.to
            applied = true
        }
    }
    root.forEachElement { element ->
        renames.firstOrNull { it.from == element.name }?.let { element.name = it.to; applied = true }
        renames.firstOrNull { it.from == element.namespace }?.let { element.namespace = it.to; applied = true }
    }
    if (!applied) return emptyList()
    return renames.filter { rename ->
        hasStringReference(rename.from, keepTargeted)
    }
}

/** True when [value] is referenced by any attribute value or raw value anywhere,
 *  ignoring the explicitly targeted [exclude] attributes. */
fun AxmlDocument.hasStringReference(value: String, exclude: Set<AxmlAttribute> = emptySet()): Boolean {
    var found = false
    forEachAttribute { attribute ->
        if (attribute in exclude) return@forEachAttribute
        if (attribute.type == ValueType.STRING && strings.getOrNull(attribute.data) == value) found = true
        if (attribute.rawValue == value) found = true
    }
    return found
}

/** True when any string-typed attribute value still points at this exact pool entry. */
fun AxmlDocument.hasStringReferenceAt(index: Int): Boolean {
    if (index < 0) return false
    var found = false
    forEachAttribute { attribute ->
        if (attribute.type == ValueType.STRING && attribute.data == index) found = true
    }
    return found
}

/**
 * Repoints [attribute] (and its raw-value slot) at a freshly allocated copy of
 * [value]. The original pool entry keeps its content, so every other attribute
 * sharing that entry is untouched.
 */
fun AxmlDocument.setAttributeString(attribute: AxmlAttribute, value: String) {
    attribute.type = ValueType.STRING
    attribute.data = addString(value)
    attribute.rawValue = value
}

internal fun AxmlDocument.forEachAttribute(action: (AxmlAttribute) -> Unit) {
    root.forEachElement { element -> element.attributes.forEach(action) }
}

internal fun AxmlElement.forEachElement(action: (AxmlElement) -> Unit) {
    action(this)
    children.forEach { it.forEachElement(action) }
}
