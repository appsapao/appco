package com.yundac.apkcloner.core.axml

/**
 * A namespace declaration carried by the document (typically only
 * `xmlns:android` for a manifest).
 */
data class Namespace(val prefix: String, val uri: String)

/** One `ResXMLTree_attribute`. */
class AxmlAttribute(
    var namespace: String?,
    var name: String,
    /** Textual form of the value when the source had one; informational only. */
    var rawValue: String?,
    var type: Int,
    /** For [ValueType.STRING] this is a string pool index; otherwise a typed value. */
    var data: Int,
)

/** One XML element together with its attributes and children. */
class AxmlElement(
    var name: String,
    var namespace: String? = null,
    var lineNumber: Int = 0,
) {
    val attributes: MutableList<AxmlAttribute> = mutableListOf()
    val children: MutableList<AxmlElement> = mutableListOf()

    fun attribute(name: String, namespace: String? = null): AxmlAttribute? =
        attributes.firstOrNull { it.name == name && it.namespace == namespace }

    /** Some manifests qualify framework attributes inconsistently; match on name only. */
    fun frameworkAttribute(name: String): AxmlAttribute? =
        attributes.firstOrNull { it.name == name && (it.namespace == null || it.namespace == AndroidAttr.NAMESPACE) }

    fun childElements(name: String): List<AxmlElement> = children.filter { it.name == name }

    fun descendants(): Sequence<AxmlElement> = sequence {
        for (child in children) {
            yield(child)
            yieldAll(child.descendants())
        }
    }

    fun addChild(child: AxmlElement): AxmlElement {
        children.add(child)
        return child
    }

    override fun toString(): String = "<$name attrs=${attributes.size} children=${children.size}>"
}

/**
 * Parsed binary Android XML document that can be edited and re-serialized.
 *
 * The document keeps the string pool and resource map in sync: every string
 * added through [addString] also reserves one resource-map slot, which is what
 * `aapt` does and what makes newly added framework attributes work.
 */
class AxmlDocument(
    val strings: StringPool,
    private val resourceIds: MutableList<Int>,
    val namespaces: MutableList<Namespace>,
    val root: AxmlElement,
    /** Whether the source document carried a resource map chunk at all. */
    val hadResourceMap: Boolean,
) {
    val resourceMapSize: Int get() = resourceIds.size

    fun resourceIdAt(stringIndex: Int): Int = resourceIds.getOrElse(stringIndex) { 0 }

    /**
     * Returns the pool index of [value], adding it (and a resource-map slot) when absent.
     * [resourceId] is only applied when the string is new, or when the existing slot is
     * still unregistered -- re-registering an already mapped name would corrupt it.
     */
    fun addString(value: String, resourceId: Int = 0): Int {
        val existing = strings.indexOf(value)
        if (existing >= 0) {
            if (resourceId != 0 && resourceIds.getOrElse(existing) { 0 } == 0) {
                resourceIds[existing] = resourceId
            }
            return existing
        }
        val index = strings.append(value)
        check(index == resourceIds.size) { "String pool and resource map are out of sync" }
        resourceIds.add(resourceId)
        return index
    }

    fun snapshotResourceIds(): IntArray = IntArray(strings.size) { resourceIds.getOrElse(it) { 0 } }

    /** Resolves a string-typed attribute to its value. */
    fun stringValue(attribute: AxmlAttribute?): String? {
        if (attribute == null) return null
        if (attribute.type != ValueType.STRING) return null
        return strings.getOrNull(attribute.data)
    }
}
