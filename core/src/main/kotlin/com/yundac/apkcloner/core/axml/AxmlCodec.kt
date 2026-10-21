package com.yundac.apkcloner.core.axml

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Parses and serializes the binary `AndroidManifest.xml` (AXML) format. */
object AxmlCodec {

    class AxmlFormatException(message: String) : Exception(message)

    fun parse(bytes: ByteArray): AxmlDocument {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        if (bytes.size < 8) throw AxmlFormatException("File is too small to be binary XML (${bytes.size} bytes)")
        if (buf.le16(0) != ChunkType.XML) {
            throw AxmlFormatException(
                "Not a binary XML document (chunk type 0x${buf.le16(0).toString(16)}). " +
                    "The APK may already contain a plain-text manifest, which this tool does not modify."
            )
        }
        val rootHeaderSize = buf.le16(2)
        val declaredSize = buf.le32(4)
        val limit = if (declaredSize in 8..bytes.size) declaredSize else bytes.size

        var pos = rootHeaderSize
        if (pos + 8 > limit) throw AxmlFormatException("Binary XML document is truncated")

        val pool = StringPoolCodec.parse(buf, pos)
        pos += StringPoolCodec.chunkSize(buf, pos)

        var hadResourceMap = false
        var resourceIds = mutableListOf<Int>()
        if (pos + 8 <= limit && buf.le16(pos) == ChunkType.XML_RESOURCE_MAP) {
            hadResourceMap = true
            val chunkSize = buf.le32(pos + 4)
            val count = (chunkSize - 8) / 4
            for (i in 0 until count) resourceIds.add(buf.le32(pos + 8 + i * 4))
            pos += chunkSize
        }
        // Keep the invariant resourceIds.size == pool.size.
        while (resourceIds.size < pool.size) resourceIds.add(0)
        if (resourceIds.size > pool.size) resourceIds = resourceIds.subList(0, pool.size).toMutableList()

        val namespaces = mutableListOf<Namespace>()
        val stack = ArrayDeque<AxmlElement>()
        var root: AxmlElement? = null

        while (pos + 8 <= limit) {
            val type = buf.le16(pos)
            val headerSize = buf.le16(pos + 2)
            val chunkSize = buf.le32(pos + 4)
            if (chunkSize < 8 || pos + chunkSize > limit) {
                throw AxmlFormatException("Corrupt chunk at offset $pos (type 0x${type.toString(16)}, size $chunkSize)")
            }
            val line = if (chunkSize >= 12) buf.le32(pos + 8) else 0

            when (type) {
                ChunkType.XML_START_NAMESPACE -> {
                    val prefix = pool.getOrNull(buf.le32(pos + 16)).orEmpty()
                    val uri = pool.getOrNull(buf.le32(pos + 20)).orEmpty()
                    if (namespaces.none { it.prefix == prefix }) namespaces.add(Namespace(prefix, uri))
                }

                ChunkType.XML_END_NAMESPACE -> Unit

                ChunkType.XML_START_ELEMENT -> {
                    val element = parseElement(buf, pool, pos, headerSize, line)
                    val parent = stack.lastOrNull()
                    if (parent == null) {
                        if (root == null) root = element
                    } else {
                        parent.children.add(element)
                    }
                    stack.addLast(element)
                }

                ChunkType.XML_END_ELEMENT -> {
                    if (stack.isEmpty()) throw AxmlFormatException("Unbalanced end-element at offset $pos")
                    stack.removeLast()
                }

                ChunkType.XML_CDATA -> throw AxmlFormatException(
                    "Binary XML with character data is not supported (offset $pos). Manifest files never contain CDATA."
                )

                else -> throw AxmlFormatException("Unexpected XML chunk type 0x${type.toString(16)} at offset $pos")
            }
            pos += chunkSize
        }

        val documentRoot = root ?: throw AxmlFormatException("Binary XML has no root element")
        return AxmlDocument(pool, resourceIds, namespaces, documentRoot, hadResourceMap)
    }

    private fun parseElement(
        buf: ByteBuffer,
        pool: StringPool,
        pos: Int,
        headerSize: Int,
        line: Int,
    ): AxmlElement {
        val nsIndex = buf.le32(pos + 16)
        val nameIndex = buf.le32(pos + 20)
        val attributeStart = buf.le16(pos + 24)
        val attributeSize = buf.le16(pos + 26)
        val attributeCount = buf.le16(pos + 28)

        val name = pool.getOrNull(nameIndex)
            ?: throw AxmlFormatException("Element name index $nameIndex is outside the string pool")
        val element = AxmlElement(
            name = name,
            namespace = if (nsIndex == -1) null else pool.getOrNull(nsIndex),
            lineNumber = line,
        )

        val base = pos + headerSize + attributeStart
        var offset = base
        for (i in 0 until attributeCount) {
            if (offset + attributeSize > buf.capacity()) {
                throw AxmlFormatException("Attribute $i of <$name> is outside the document")
            }
            val attrNs = buf.le32(offset)
            val attrName = buf.le32(offset + 4)
            val rawValue = buf.le32(offset + 8)
            val valueType = buf.byteAt(offset + 15)
            val valueData = buf.le32(offset + 16)

            element.attributes.add(
                AxmlAttribute(
                    namespace = if (attrNs == -1) null else pool.getOrNull(attrNs),
                    name = pool.getOrNull(attrName) ?: throw AxmlFormatException("Attribute name index out of range"),
                    rawValue = if (rawValue == -1) null else pool.getOrNull(rawValue),
                    type = valueType,
                    data = valueData,
                )
            )
            offset += attributeSize
        }
        return element
    }

    fun encode(document: AxmlDocument): ByteArray {
        // The body is serialized first, because writing it may register new
        // strings (element/attribute names, namespace URIs) in the pool. The pool
        // and the resource map must be serialized *after* that, or the document
        // would reference strings that are not in the file.
        val body = ByteArrayOutputStream(4096)
        document.namespaces.forEach { namespace ->
            writeNodeHeader(
                body,
                type = ChunkType.XML_START_NAMESPACE,
                size = 24,
                lineNumber = 0,
            )
            body.writeIntLe(document.addString(namespace.prefix))
            body.writeIntLe(document.addString(namespace.uri))
        }

        writeElement(body, document, document.root)

        document.namespaces.forEach { namespace ->
            writeNodeHeader(
                body,
                type = ChunkType.XML_END_NAMESPACE,
                size = 24,
                lineNumber = 0,
            )
            body.writeIntLe(document.addString(namespace.prefix))
            body.writeIntLe(document.addString(namespace.uri))
        }

        val bodyBytes = body.toByteArray()
        val poolBytes = StringPoolCodec.encode(document.strings)
        val resourceIds = document.snapshotResourceIds()
        val writeResourceMap = document.hadResourceMap || resourceIds.any { it != 0 }

        val resourceMapBytes = if (writeResourceMap && resourceIds.isNotEmpty()) {
            val chunk = ByteArray(8 + resourceIds.size * 4)
            val wrapper = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
            wrapper.putShort(ChunkType.XML_RESOURCE_MAP.toShort())
            wrapper.putShort(8)
            wrapper.putInt(chunk.size)
            resourceIds.forEach { wrapper.putInt(it) }
            chunk
        } else {
            ByteArray(0)
        }

        val total = 8 + poolBytes.size + resourceMapBytes.size + bodyBytes.size

        val out = littleEndian(total)
        out.putShort(ChunkType.XML.toShort())
        out.putShort(8)
        out.putInt(total)
        out.put(poolBytes)
        out.put(resourceMapBytes)
        out.put(bodyBytes)
        return out.array()
    }

    private fun writeElement(body: ByteArrayOutputStream, document: AxmlDocument, element: AxmlElement) {
        val attributes = element.attributes
        val size = 16 + 20 + attributes.size * 20
        writeNodeHeader(body, ChunkType.XML_START_ELEMENT, size, element.lineNumber)
        body.writeIntLe(element.namespace?.let { document.addString(it) } ?: -1)
        body.writeIntLe(document.addString(element.name))
        body.writeShortLe(20) // attributeStart, relative to the attrExt struct
        body.writeShortLe(20) // attributeSize
        body.writeShortLe(attributes.size)
        body.writeShortLe(0) // idIndex
        body.writeShortLe(0) // classIndex
        body.writeShortLe(0) // styleIndex

        attributes.forEach { attribute ->
            body.writeIntLe(attribute.namespace?.let { document.addString(it) } ?: -1)
            body.writeIntLe(document.addString(attribute.name))
            val rawIndex = attribute.rawValue?.let { document.addString(it) } ?: -1
            body.writeIntLe(rawIndex)
            // Res_value
            body.writeShortLe(ValueType.SIZE)
            body.write(0) // res0
            body.write(attribute.type)
            body.writeIntLe(attribute.data)
        }

        element.children.forEach { writeElement(body, document, it) }

        writeNodeHeader(body, ChunkType.XML_END_ELEMENT, 24, element.lineNumber)
        body.writeIntLe(element.namespace?.let { document.addString(it) } ?: -1)
        body.writeIntLe(document.addString(element.name))
    }

    private fun writeNodeHeader(body: ByteArrayOutputStream, type: Int, size: Int, lineNumber: Int) {
        body.writeShortLe(type)
        body.writeShortLe(16) // headerSize for XML nodes
        body.writeIntLe(size)
        body.writeIntLe(lineNumber)
        body.writeIntLe(-1) // comment index
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }
}
