package com.yundac.apkcloner.core.support

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Independent binary-XML writer used only by tests.
 *
 * It is deliberately *not* built on top of the production `AxmlCodec`, so a test
 * that parses these bytes exercises the parser against an implementation that
 * shares no code with it. The real-world counterpart to this file is
 * `Aapt2InteropTest`, which validates against AOSP's own tooling.
 */
object AxmlTestFactory {

    const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    const val RES_NAME = 0x01010003
    const val RES_LABEL = 0x01010001
    const val RES_ICON = 0x01010002
    const val RES_AUTHORITIES = 0x01010018
    const val RES_SHARED_USER_ID = 0x0101000b
    const val RES_VERSION_CODE = 0x0101021b
    const val RES_VERSION_NAME = 0x0101021c
    const val RES_MIN_SDK = 0x0101020c
    const val RES_TARGET_SDK = 0x01010270
    const val RES_ALLOW_BACKUP = 0x01010280
    const val RES_EXPORTED = 0x01010010

    class Attr(
        val name: String,
        val stringValue: String? = null,
        val intValue: Int? = null,
        val booleanValue: Boolean? = null,
        val reference: Int? = null,
        val namespace: String? = ANDROID_NS,
        val resourceId: Int = 0,
    )

    class Element(
        val name: String,
        val attributes: List<Attr> = emptyList(),
        val children: List<Element> = emptyList(),
    )

    private class StringTable(private val utf16: Boolean) {
        val values = mutableListOf<String>()
        val resourceIds = mutableListOf<Int>()

        fun add(value: String, resourceId: Int = 0): Int {
            val existing = values.indexOf(value)
            if (existing >= 0) {
                if (resourceId != 0 && resourceIds[existing] == 0) resourceIds[existing] = resourceId
                return existing
            }
            values.add(value)
            resourceIds.add(resourceId)
            return values.size - 1
        }
    }

    fun encode(
        root: Element,
        utf16: Boolean = false,
        namespaces: List<Pair<String, String>> = listOf("android" to ANDROID_NS),
        includeResourceMap: Boolean = true,
    ): ByteArray {
        val table = StringTable(utf16)
        val body = ByteArrayOutputStream()

        namespaces.forEach { (prefix, uri) ->
            nodeHeader(body, 0x0100, 24, 0)
            body.putInt(table.add(prefix))
            body.putInt(table.add(uri))
        }
        writeElement(body, table, root)
        namespaces.forEach { (prefix, uri) ->
            nodeHeader(body, 0x0101, 24, 0)
            body.putInt(table.add(prefix))
            body.putInt(table.add(uri))
        }

        val pool = encodePool(table.values, utf16)
        val bodyBytes = body.toByteArray()
        val resourceMap = if (includeResourceMap) encodeResourceMap(table.resourceIds) else ByteArray(0)
        val total = 8 + pool.size + resourceMap.size + bodyBytes.size

        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(0x0003)
        out.putShort(8)
        out.putInt(total)
        out.put(pool)
        out.put(resourceMap)
        out.put(bodyBytes)
        return out.array()
    }

    private fun writeElement(out: ByteArrayOutputStream, table: StringTable, element: Element) {
        val size = 16 + 20 + element.attributes.size * 20
        nodeHeader(out, 0x0102, size, 0)
        out.putInt(-1) // element namespace
        out.putInt(table.add(element.name))
        out.putShort(20)
        out.putShort(20)
        out.putShort(element.attributes.size)
        out.putShort(0)
        out.putShort(0)
        out.putShort(0)

        element.attributes.forEach { attribute ->
            out.putInt(attribute.namespace?.let { table.add(it) } ?: -1)
            out.putInt(table.add(attribute.name, attribute.resourceId))
            out.putInt(-1) // rawValue
            out.putShort(8) // Res_value size
            out.write(0) // res0
            val (type, data) = when {
                attribute.stringValue != null -> 0x03 to table.add(attribute.stringValue)
                attribute.reference != null -> 0x01 to attribute.reference
                attribute.booleanValue != null -> 0x12 to if (attribute.booleanValue) -1 else 0
                attribute.intValue != null -> 0x10 to attribute.intValue
                else -> 0x00 to 0
            }
            out.write(type)
            out.putInt(data)
        }

        element.children.forEach { writeElement(out, table, it) }

        nodeHeader(out, 0x0103, 24, 0)
        out.putInt(-1)
        out.putInt(table.add(element.name))
    }

    private fun nodeHeader(out: ByteArrayOutputStream, type: Int, size: Int, line: Int) {
        out.putShort(type)
        out.putShort(16)
        out.putInt(size)
        out.putInt(line)
        out.putInt(-1)
    }

    private fun encodePool(values: List<String>, utf16: Boolean): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = values.map { value ->
            val start = data.size()
            if (utf16) {
                data.putShort(value.length)
                value.forEach { char ->
                    data.write(char.code and 0xFF)
                    data.write((char.code shr 8) and 0xFF)
                }
                data.write(0)
                data.write(0)
            } else {
                val bytes = value.toByteArray(Charsets.UTF_8)
                data.write(value.length)
                data.write(bytes.size)
                data.write(bytes)
                data.write(0)
            }
            start
        }
        while (data.size() % 4 != 0) data.write(0)

        val headerSize = 28
        val stringsStart = headerSize + values.size * 4
        val total = stringsStart + data.size()
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(0x0001.toShort())
        out.putShort(headerSize.toShort())
        out.putInt(total)
        out.putInt(values.size)
        out.putInt(0)
        out.putInt(if (utf16) 0 else 0x100)
        out.putInt(stringsStart)
        out.putInt(0)
        offsets.forEach { out.putInt(it) }
        out.put(data.toByteArray())
        return out.array()
    }

    private fun encodeResourceMap(resourceIds: List<Int>): ByteArray {
        val out = ByteBuffer.allocate(8 + resourceIds.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(0x0180.toShort())
        out.putShort(8.toShort())
        out.putInt(8 + resourceIds.size * 4)
        resourceIds.forEach { out.putInt(it) }
        return out.array()
    }

    private fun ByteArrayOutputStream.putShort(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.putInt(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }
}

/** Ready-made manifests used by several tests. */
object TestManifests {

    fun standard(
        packageName: String = "com.example.source",
        versionCode: Int = 7,
        versionName: String = "1.2.3",
        minSdk: Int = 26,
        targetSdk: Int = 35,
        labelLiteral: String? = "Source App",
        labelReference: Int? = null,
        permissions: List<String> = listOf(
            "android.permission.INTERNET",
            "android.permission.CAMERA",
        ),
        declaredPermission: String? = "com.example.source.permission.C2D",
        sharedUserId: String? = null,
        split: String? = null,
        providerAuthority: String = "com.example.source.fileprovider",
        launcherComponent: String = "com.example.source.MainActivity",
        secondaryActivityWithLabel: String? = null,
        allowBackup: Boolean? = null,
        utf16: Boolean = false,
    ): ByteArray {
        val manifestAttributes = mutableListOf(
            AxmlTestFactory.Attr(name = "package", stringValue = packageName, namespace = null),
            AxmlTestFactory.Attr(name = "versionCode", intValue = versionCode, resourceId = AxmlTestFactory.RES_VERSION_CODE),
            AxmlTestFactory.Attr(name = "versionName", stringValue = versionName, resourceId = AxmlTestFactory.RES_VERSION_NAME),
        )
        if (sharedUserId != null) {
            manifestAttributes += AxmlTestFactory.Attr(
                name = "sharedUserId",
                stringValue = sharedUserId,
                resourceId = AxmlTestFactory.RES_SHARED_USER_ID,
            )
        }
        if (split != null) {
            manifestAttributes += AxmlTestFactory.Attr(name = "split", stringValue = split, namespace = null)
        }

        val children = mutableListOf<AxmlTestFactory.Element>()
        children += AxmlTestFactory.Element(
            name = "uses-sdk",
            attributes = listOf(
                AxmlTestFactory.Attr(name = "minSdkVersion", intValue = minSdk, resourceId = AxmlTestFactory.RES_MIN_SDK),
                AxmlTestFactory.Attr(name = "targetSdkVersion", intValue = targetSdk, resourceId = AxmlTestFactory.RES_TARGET_SDK),
            ),
        )
        permissions.forEach { permission ->
            children += AxmlTestFactory.Element(
                name = "uses-permission",
                attributes = listOf(
                    AxmlTestFactory.Attr(name = "name", stringValue = permission, resourceId = AxmlTestFactory.RES_NAME),
                ),
            )
        }
        if (declaredPermission != null) {
            children += AxmlTestFactory.Element(
                name = "permission",
                attributes = listOf(
                    AxmlTestFactory.Attr(name = "name", stringValue = declaredPermission, resourceId = AxmlTestFactory.RES_NAME),
                ),
            )
        }

        val applicationAttributes = mutableListOf<AxmlTestFactory.Attr>()
        when {
            labelLiteral != null -> applicationAttributes += AxmlTestFactory.Attr(
                name = "label",
                stringValue = labelLiteral,
                resourceId = AxmlTestFactory.RES_LABEL,
            )
            labelReference != null -> applicationAttributes += AxmlTestFactory.Attr(
                name = "label",
                reference = labelReference,
                resourceId = AxmlTestFactory.RES_LABEL,
            )
        }
        applicationAttributes += AxmlTestFactory.Attr(
            name = "name",
            stringValue = "com.example.source.SourceApplication",
            resourceId = AxmlTestFactory.RES_NAME,
        )
        if (allowBackup != null) {
            applicationAttributes += AxmlTestFactory.Attr(
                name = "allowBackup",
                booleanValue = allowBackup,
                resourceId = AxmlTestFactory.RES_ALLOW_BACKUP,
            )
        }

        val applicationChildren = mutableListOf<AxmlTestFactory.Element>()
        applicationChildren += launcher(packageName, launcherComponent)
        if (secondaryActivityWithLabel != null) {
            applicationChildren += AxmlTestFactory.Element(
                name = "activity",
                attributes = listOf(
                    AxmlTestFactory.Attr(
                        name = "name",
                        stringValue = secondaryActivityWithLabel,
                        resourceId = AxmlTestFactory.RES_NAME,
                    ),
                    AxmlTestFactory.Attr(
                        name = "label",
                        stringValue = "Original Settings Label",
                        resourceId = AxmlTestFactory.RES_LABEL,
                    ),
                ),
            )
        }
        applicationChildren += AxmlTestFactory.Element(
            name = "provider",
            attributes = listOf(
                AxmlTestFactory.Attr(
                    name = "name",
                    stringValue = "$packageName.Provider",
                    resourceId = AxmlTestFactory.RES_NAME,
                ),
                AxmlTestFactory.Attr(
                    name = "authorities",
                    stringValue = providerAuthority,
                    resourceId = AxmlTestFactory.RES_AUTHORITIES,
                ),
            ),
        )

        children += AxmlTestFactory.Element(
            name = "application",
            attributes = applicationAttributes,
            children = applicationChildren,
        )

        return AxmlTestFactory.encode(
            root = AxmlTestFactory.Element(name = "manifest", attributes = manifestAttributes, children = children),
            utf16 = utf16,
        )
    }

    private fun launcher(packageName: String, component: String): AxmlTestFactory.Element =
        AxmlTestFactory.Element(
            name = "activity",
            attributes = listOf(
                AxmlTestFactory.Attr(name = "name", stringValue = component, resourceId = AxmlTestFactory.RES_NAME),
                AxmlTestFactory.Attr(
                    name = "label",
                    stringValue = "Original Launcher Label",
                    resourceId = AxmlTestFactory.RES_LABEL,
                ),
                AxmlTestFactory.Attr(name = "exported", booleanValue = true, resourceId = AxmlTestFactory.RES_EXPORTED),
            ),
            children = listOf(
                AxmlTestFactory.Element(
                    name = "intent-filter",
                    children = listOf(
                        AxmlTestFactory.Element(
                            name = "action",
                            attributes = listOf(
                                AxmlTestFactory.Attr(
                                    name = "name",
                                    stringValue = "android.intent.action.MAIN",
                                    resourceId = AxmlTestFactory.RES_NAME,
                                ),
                            ),
                        ),
                        AxmlTestFactory.Element(
                            name = "category",
                            attributes = listOf(
                                AxmlTestFactory.Attr(
                                    name = "name",
                                    stringValue = "android.intent.category.LAUNCHER",
                                    resourceId = AxmlTestFactory.RES_NAME,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
}
