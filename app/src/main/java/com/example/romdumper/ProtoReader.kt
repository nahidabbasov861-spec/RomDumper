package com.example.romdumper

import java.io.IOException

/**
 * Minimal, generic Protocol Buffers (proto2 wire-format) reader.
 *
 * This does not depend on any .proto-generated code or any third-party
 * protobuf library. It just walks the raw bytes following the standard
 * tag/wire-type/value encoding described publicly at
 * https://protobuf.dev/programming-guides/encoding/
 *
 * It is generic: it has no knowledge of any specific message schema. The
 * caller interprets the returned field map according to whichever message
 * definition it is decoding (see PayloadParser.kt for the schema used by
 * Android's update_engine payload.bin format).
 */
object ProtoReader {

    /**
     * Parses [length] bytes of [data] starting at [offset] into a map of
     * field number -> list of raw values (supports repeated fields).
     *
     * Value types stored per wire type:
     *  - wire type 0 (varint)           -> Long
     *  - wire type 1 (64-bit fixed)     -> Long (raw bits)
     *  - wire type 2 (length-delimited) -> ByteArray (string / bytes / embedded message)
     *  - wire type 5 (32-bit fixed)     -> Int (raw bits)
     */
    fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size): Map<Int, MutableList<Any>> {
        val result = HashMap<Int, MutableList<Any>>()
        var pos = offset
        val end = offset + length

        while (pos < end) {
            val (tag, afterTag) = readVarint(data, pos)
            pos = afterTag

            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x7L).toInt()

            when (wireType) {
                0 -> {
                    val (value, next) = readVarint(data, pos)
                    pos = next
                    result.getOrPut(fieldNumber) { mutableListOf() }.add(value)
                }
                1 -> {
                    val value = readFixed64(data, pos)
                    pos += 8
                    result.getOrPut(fieldNumber) { mutableListOf() }.add(value)
                }
                2 -> {
                    val (len, next) = readVarint(data, pos)
                    pos = next
                    val l = len.toInt()
                    val slice = data.copyOfRange(pos, pos + l)
                    pos += l
                    result.getOrPut(fieldNumber) { mutableListOf() }.add(slice)
                }
                5 -> {
                    val value = readFixed32(data, pos)
                    pos += 4
                    result.getOrPut(fieldNumber) { mutableListOf() }.add(value)
                }
                else -> throw IOException("Naməlum protobuf wire type: $wireType (sahə $fieldNumber)")
            }
        }
        return result
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = start
        while (true) {
            val b = data[pos].toInt() and 0xFF
            result = result or ((b.toLong() and 0x7F) shl shift)
            pos++
            if (b and 0x80 == 0) break
            shift += 7
        }
        return Pair(result, pos)
    }

    private fun readFixed32(data: ByteArray, pos: Int): Int {
        return (data[pos].toInt() and 0xFF) or
            ((data[pos + 1].toInt() and 0xFF) shl 8) or
            ((data[pos + 2].toInt() and 0xFF) shl 16) or
            ((data[pos + 3].toInt() and 0xFF) shl 24)
    }

    private fun readFixed64(data: ByteArray, pos: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
        }
        return v
    }
}

// Convenience accessors for the generic field map -----------------------

fun Map<Int, MutableList<Any>>.lastBytes(field: Int): ByteArray? =
    this[field]?.lastOrNull() as? ByteArray

fun Map<Int, MutableList<Any>>.lastString(field: Int): String? =
    lastBytes(field)?.toString(Charsets.UTF_8)

fun Map<Int, MutableList<Any>>.lastLong(field: Int): Long? =
    this[field]?.lastOrNull() as? Long

fun Map<Int, MutableList<Any>>.messages(field: Int): List<ByteArray> =
    this[field]?.filterIsInstance<ByteArray>() ?: emptyList()
