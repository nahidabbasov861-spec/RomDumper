package com.example.romdumper

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Reader/extractor for Android's A/B OTA "payload.bin" format
 * (system/update_engine, AOSP, Apache-2.0 licensed public specification).
 *
 * Only FULL payloads are supported (minor_version == 0): REPLACE,
 * REPLACE_BZ, REPLACE_XZ and ZERO operations. Incremental/delta payloads
 * (which patch an existing on-device partition) are intentionally not
 * supported, since there is no source partition available when just
 * dumping images out of a downloaded OTA file.
 */

data class Extent(val startBlock: Long, val numBlocks: Long)

data class InstallOp(
    val type: Int,
    val dataOffset: Long,
    val dataLength: Long,
    val dstExtents: List<Extent>
)

data class PartitionEntry(
    val name: String,
    val newSize: Long,
    val operations: List<InstallOp>
)

data class PayloadManifest(
    val blockSize: Long,
    val minorVersion: Long,
    val blobOffset: Long,
    val partitions: List<PartitionEntry>
)

object InstallOpType {
    const val REPLACE = 0
    const val REPLACE_BZ = 1
    const val SOURCE_COPY = 4
    const val SOURCE_BSDIFF = 5
    const val ZERO = 6
    const val DISCARD = 7
    const val REPLACE_XZ = 8
    const val PUFFDIFF = 9
}

object PayloadParser {

    // DeltaArchiveManifest field numbers (see update_metadata.proto)
    private const val F_BLOCK_SIZE = 3
    private const val F_MINOR_VERSION = 12
    private const val F_PARTITIONS = 13

    // PartitionUpdate field numbers
    private const val F_PART_NAME = 1
    private const val F_PART_NEW_INFO = 7
    private const val F_PART_OPERATIONS = 8

    // PartitionInfo field numbers
    private const val F_INFO_SIZE = 1

    // InstallOperation field numbers
    private const val F_OP_TYPE = 1
    private const val F_OP_DATA_OFFSET = 2
    private const val F_OP_DATA_LENGTH = 3
    private const val F_OP_DST_EXTENTS = 6

    // Extent field numbers
    private const val F_EXT_START_BLOCK = 1
    private const val F_EXT_NUM_BLOCKS = 2

    fun parseHeader(payloadFile: File): PayloadManifest {
        RandomAccessFile(payloadFile, "r").use { raf ->
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "CrAU") {
                throw IOException("Bu fayl payload.bin formatında deyil (CrAU imzası tapılmadı)")
            }

            // All header integers are big-endian; RandomAccessFile reads
            // multi-byte values big-endian by default (DataInput contract).
            val fileFormatVersion = raf.readLong()
            val manifestSize = raf.readLong()
            var metadataSignatureSize = 0
            if (fileFormatVersion > 1) {
                metadataSignatureSize = raf.readInt()
            }

            val manifestBytes = ByteArray(manifestSize.toInt())
            raf.readFully(manifestBytes)
            if (metadataSignatureSize > 0) {
                raf.skipBytes(metadataSignatureSize)
            }
            val blobOffset = raf.filePointer

            val fields = ProtoReader.parse(manifestBytes)
            val blockSize = fields.lastLong(F_BLOCK_SIZE) ?: 4096L
            val minorVersion = fields.lastLong(F_MINOR_VERSION) ?: 0L
            val partitions = fields.messages(F_PARTITIONS).map { parsePartition(it) }

            return PayloadManifest(blockSize, minorVersion, blobOffset, partitions)
        }
    }

    private fun parsePartition(raw: ByteArray): PartitionEntry {
        val f = ProtoReader.parse(raw)
        val name = f.lastString(F_PART_NAME) ?: "naməlum"
        val newInfoBytes = f.lastBytes(F_PART_NEW_INFO)
        val newSize = if (newInfoBytes != null) {
            ProtoReader.parse(newInfoBytes).lastLong(F_INFO_SIZE) ?: 0L
        } else 0L
        val ops = f.messages(F_PART_OPERATIONS).map { parseOperation(it) }
        return PartitionEntry(name, newSize, ops)
    }

    private fun parseOperation(raw: ByteArray): InstallOp {
        val f = ProtoReader.parse(raw)
        val type = (f.lastLong(F_OP_TYPE) ?: 0L).toInt()
        val dataOffset = f.lastLong(F_OP_DATA_OFFSET) ?: 0L
        val dataLength = f.lastLong(F_OP_DATA_LENGTH) ?: 0L
        val dstExtents = f.messages(F_OP_DST_EXTENTS).map { e ->
            val ef = ProtoReader.parse(e)
            Extent(ef.lastLong(F_EXT_START_BLOCK) ?: 0L, ef.lastLong(F_EXT_NUM_BLOCKS) ?: 0L)
        }
        return InstallOp(type, dataOffset, dataLength, dstExtents)
    }

    /**
     * Extracts a single partition image from [payloadFile] into [outFile],
     * applying each of its install operations in order. Calls [onProgress]
     * with a 0..1 fraction after each operation.
     */
    fun extractPartition(
        payloadFile: File,
        manifest: PayloadManifest,
        partition: PartitionEntry,
        outFile: File,
        onProgress: (Float) -> Unit
    ) {
        RandomAccessFile(payloadFile, "r").use { src ->
            RandomAccessFile(outFile, "rw").use { out ->
                out.setLength(partition.newSize)
                val total = partition.operations.size.coerceAtLeast(1)

                partition.operations.forEachIndexed { index, op ->
                    when (op.type) {
                        InstallOpType.ZERO -> writeZeros(out, op.dstExtents, manifest.blockSize)

                        InstallOpType.REPLACE,
                        InstallOpType.REPLACE_BZ,
                        InstallOpType.REPLACE_XZ -> {
                            src.seek(manifest.blobOffset + op.dataOffset)
                            val raw = ByteArray(op.dataLength.toInt())
                            src.readFully(raw)
                            val payload = when (op.type) {
                                InstallOpType.REPLACE_BZ ->
                                    BZip2CompressorInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
                                InstallOpType.REPLACE_XZ ->
                                    XZInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
                                else -> raw
                            }
                            writeAcrossExtents(out, payload, op.dstExtents, manifest.blockSize)
                        }

                        InstallOpType.SOURCE_COPY, InstallOpType.SOURCE_BSDIFF, InstallOpType.PUFFDIFF ->
                            throw IOException(
                                "Bu, artımlı (delta) OTA paketidir — yalnız tam (full) " +
                                    "payload.bin dəstəklənir."
                            )

                        InstallOpType.DISCARD -> { /* no-op: destination left as-is (zero) */ }

                        else -> throw IOException("Dəstəklənməyən əməliyyat növü: ${op.type}")
                    }
                    onProgress((index + 1).toFloat() / total)
                }
            }
        }
    }

    private fun writeZeros(out: RandomAccessFile, extents: List<Extent>, blockSize: Long) {
        val zeros = ByteArray(minOf(1 shl 20, Int.MAX_VALUE))
        for (ext in extents) {
            out.seek(ext.startBlock * blockSize)
            var remaining = ext.numBlocks * blockSize
            while (remaining > 0) {
                val chunk = minOf(remaining, zeros.size.toLong()).toInt()
                out.write(zeros, 0, chunk)
                remaining -= chunk
            }
        }
    }

    private fun writeAcrossExtents(out: RandomAccessFile, data: ByteArray, extents: List<Extent>, blockSize: Long) {
        var srcPos = 0
        for (ext in extents) {
            if (srcPos >= data.size) break
            out.seek(ext.startBlock * blockSize)
            val maxLen = (ext.numBlocks * blockSize).toInt()
            val toWrite = minOf(maxLen, data.size - srcPos)
            out.write(data, srcPos, toWrite)
            srcPos += toWrite
        }
    }
}
