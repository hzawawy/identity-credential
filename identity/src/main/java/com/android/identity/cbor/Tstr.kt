package com.android.identity.cbor

import kotlinx.io.bytestring.ByteStringBuilder

/**
 * Unicode String (major type 3).
 *
 * @param value the [String] for the value of the byte string.
 */
class Tstr(val value: String) : DataItem(MajorType.UNICODE_STRING) {
    override fun encode(builder: ByteStringBuilder) {
        val encodedValue = value.encodeToByteArray()
        Cbor.encodeLength(builder, majorType, encodedValue.size)
        builder.append(value.encodeToByteArray())
    }

    companion object {
        internal fun decode(encodedCbor: ByteArray, offset: Int): Pair<Int, Tstr> {
            val (newOffset, length) = Cbor.decodeLength(encodedCbor, offset)
            val payloadBegin = newOffset
            val payloadEnd = newOffset + length.toInt()
            val slice = encodedCbor.sliceArray(IntRange(payloadBegin, payloadEnd - 1))
            return Pair(payloadEnd, Tstr(String(slice)))
        }
    }

    override fun equals(other: Any?): Boolean = other is Tstr && value.equals(other.value)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String {
        return "Tstr(\"$value\")"
    }
}