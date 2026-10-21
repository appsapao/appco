package com.yundac.apkcloner.core.axml

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Chunk types used by Android's binary XML (AXML) and resource formats. */
object ChunkType {
    const val NULL: Int = 0x0000
    const val STRING_POOL: Int = 0x0001
    const val TABLE: Int = 0x0002
    const val XML: Int = 0x0003

    const val XML_START_NAMESPACE: Int = 0x0100
    const val XML_END_NAMESPACE: Int = 0x0101
    const val XML_START_ELEMENT: Int = 0x0102
    const val XML_END_ELEMENT: Int = 0x0103
    const val XML_CDATA: Int = 0x0104

    const val XML_RESOURCE_MAP: Int = 0x0180
}

object PoolFlag {
    const val SORTED: Int = 0x00000001
    const val UTF8: Int = 0x00000100
}

/** `Res_value.dataType` values that occur in manifests. */
object ValueType {
    const val NULL: Int = 0x00
    const val REFERENCE: Int = 0x01
    const val ATTRIBUTE: Int = 0x02
    const val STRING: Int = 0x03
    const val FLOAT: Int = 0x04
    const val INT_DEC: Int = 0x10
    const val INT_HEX: Int = 0x11
    const val INT_BOOLEAN: Int = 0x12

    /** Size of a serialized `Res_value` in bytes. */
    const val SIZE: Int = 8
}

/**
 * Framework attribute resource IDs. Android identifies framework attributes by
 * resource ID rather than by name, so any *newly added* attribute name must be
 * registered in the resource map with its ID.
 */
object AndroidAttr {
    const val NAMESPACE: String = "http://schemas.android.com/apk/res/android"

    const val NAME: Int = 0x01010003
    const val LABEL: Int = 0x01010001
    const val ICON: Int = 0x01010002
    const val SHARED_USER_ID: Int = 0x0101000b
    const val DEBUGGABLE: Int = 0x0101000f
    const val EXPORTED: Int = 0x01010010
    const val AUTHORITIES: Int = 0x01010018
    const val MIN_SDK_VERSION: Int = 0x0101020c
    const val VERSION_CODE: Int = 0x0101021b
    const val VERSION_NAME: Int = 0x0101021c
    const val TARGET_SDK_VERSION: Int = 0x01010270
    const val ALLOW_BACKUP: Int = 0x01010280
    const val EXTRACT_NATIVE_LIBS: Int = 0x010104ea
    const val USES_CLEARTEXT_TRAFFIC: Int = 0x010104ec
}

internal fun ByteBuffer.le16(offset: Int): Int = getShort(offset).toInt() and 0xFFFF
internal fun ByteBuffer.le32(offset: Int): Int = getInt(offset)
internal fun ByteBuffer.leU32(offset: Int): Long = getInt(offset).toLong() and 0xFFFFFFFFL
internal fun ByteBuffer.byteAt(offset: Int): Int = get(offset).toInt() and 0xFF

internal fun littleEndian(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
