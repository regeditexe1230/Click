package com.yjc.click

import android.graphics.Typeface
import java.io.File
import java.io.RandomAccessFile

/**
 * 解析 OpenType/TTF 字体文件的 cmap 表，检查是否支持当前语言的 Unicode 字符。
 */
object FontParser {

    data class FontInfo(
        val familyName: String,
        val filePath: String,
        val isCustom: Boolean = true
    )

    fun loadTypeface(file: File): Typeface? {
        return try {
            Typeface.createFromFile(file)
        } catch (e: Exception) {
            null
        }
    }

    fun getFontFamilyName(file: File): String? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val nameOffset = findTableOffset(raf, "name") ?: return null
                readFontFamilyName(raf, nameOffset)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 检查字体是否支持指定语言的字符覆盖。
     * languageTag: "zh" / "en" / "ja" / "ko"
     */
    fun supportsLanguage(file: File, languageTag: String): Boolean {
        return languageTag in getSupportedLanguages(file)
    }

    /**
     * 读取整个 cmap 表，返回字体支持的语言列表。
     * 直接读取 cmap 段表，检查各语言区间是否有段覆盖。
     */
    fun getSupportedLanguages(file: File): List<String> {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val cmapOffset = findTableOffset(raf, "cmap") ?: return listOf("en")
                // 英文始终视为支持（几乎所有字体都能渲染Basic Latin）
                val langs = mutableListOf("en")
                langs.addAll(readCmapForLanguages(raf, cmapOffset))
                langs
            }
        } catch (e: Exception) {
            listOf("en")
        }
    }

    private val languageRanges = mapOf(
        "ja" to listOf(0x3040L..0x309FL, 0x30A0L..0x30FFL, 0x4E00L..0x9FFFL),
        "ko" to listOf(0xAC00L..0xD7AFL), // 仅Hangul，不含CJK
        "zh" to listOf(0x4E00L..0x9FFFL, 0x3400L..0x4DBFL)
    )

    private fun readCmapForLanguages(raf: RandomAccessFile, cmapOffset: Long): List<String> {
        raf.seek(cmapOffset)
        raf.skipBytes(2)
        val numSubtables = raf.readUnsignedShort()

        var bestOffset = -1L
        for (i in 0 until numSubtables) {
            val platformID = raf.readUnsignedShort()
            val encodingID = raf.readUnsignedShort()
            val subtableOffset = cmapOffset + raf.readUnsignedInt()
            if (platformID == 0) { bestOffset = subtableOffset; break }
            if (platformID == 3 && (encodingID == 1 || encodingID == 10)) bestOffset = subtableOffset
        }
        if (bestOffset < 0) return emptyList()

        raf.seek(bestOffset)
        val format = raf.readUnsignedShort()

        return when (format) {
            4 -> readFormat4Languages(raf, bestOffset)
            12 -> readFormat12Languages(raf, bestOffset)
            else -> emptyList()
        }
    }

    private fun readFormat4Languages(raf: RandomAccessFile, start: Long): List<String> {
        raf.seek(start)
        // Header: format(2) + language(2) + reserved(2) + segCountX2(2) + searchRange(2) + entrySelector(2) + rangeShift(2) = 16字节
        val buf = ByteArray(16)
        raf.readFully(buf)
        val segCount = (((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)) / 2
        if (segCount == 0) return emptyList()

        raf.seek(start + 16)
        val endCodes = IntArray(segCount) { raf.readUnsignedShort() }
        raf.skipBytes(2) // reservedPad
        val startCodes = IntArray(segCount) { raf.readUnsignedShort() }
        raf.skipBytes(segCount * 2) // idDeltas
        raf.skipBytes(segCount * 2) // idRangeOffsets

        val result = mutableListOf<String>()
        for ((lang, ranges) in languageRanges) {
            var supported = false
            for (range in ranges) {
                if (range.first > 0xFFFF) continue
                val rEnd = minOf(range.last.toInt(), 0xFFFF)
                for (seg in 0 until segCount - 1) { // 跳过最后一个哨兵段
                    if (startCodes[seg] <= rEnd && endCodes[seg] >= range.first.toInt()) {
                        supported = true; break
                    }
                }
                if (supported) break
            }
            if (supported) result.add(lang)
        }
        return result
    }

    private fun readFormat12Languages(raf: RandomAccessFile, start: Long): List<String> {
        raf.seek(start + 4)
        raf.readInt()
        raf.readInt()
        val numGroups = raf.readInt()

        val groups = Array(numGroups) {
            Triple(raf.readUnsignedInt(), raf.readUnsignedInt(), raf.readUnsignedInt())
        }

        val result = mutableListOf<String>()
        for ((lang, ranges) in languageRanges) {
            var supported = false
            for (range in ranges) {
                for ((gStart, gEnd, _) in groups) {
                    if (gStart <= range.last && gEnd >= range.first) {
                        supported = true; break
                    }
                }
                if (supported) break
            }
            if (supported) result.add(lang)
        }
        return result
    }

    private fun findTableOffset(raf: RandomAccessFile, tag: String): Long? {
        raf.seek(0)
        val sfVersion = raf.readInt()
        val numTables: Int
        if (sfVersion == 0x00010000 || sfVersion == 0x4F54544F) {
            numTables = raf.readUnsignedShort()
            raf.skipBytes(6) // searchRange, entrySelector, rangeShift
        } else {
            return null
        }

        for (i in 0 until numTables) {
            val tableTag = ByteArray(4)
            raf.readFully(tableTag)
            val tableName = String(tableTag, Charsets.US_ASCII)
            raf.skipBytes(4) // checksum
            val offset = raf.readUnsignedInt()
            raf.skipBytes(4) // length
            if (tableName == tag) return offset
        }
        return null
    }

    private fun readFontFamilyName(raf: RandomAccessFile, nameTableOffset: Long): String? {
        raf.seek(nameTableOffset)
        val format = raf.readUnsignedShort()
        val count = raf.readUnsignedShort()
        val stringOffset = raf.readUnsignedShort()

        for (i in 0 until count) {
            val platformID = raf.readUnsignedShort()
            val encodingID = raf.readUnsignedShort()
            val languageID = raf.readUnsignedShort()
            val nameID = raf.readUnsignedShort()
            val length = raf.readUnsignedShort()
            val offset = raf.readUnsignedShort()

            if (nameID == 1) { // Font Family name
                val savedPos = raf.filePointer
                raf.seek(nameTableOffset + stringOffset + offset)
                val nameBytes = ByteArray(length)
                raf.readFully(nameBytes)
                raf.seek(savedPos)

                // Try Unicode (platform 3, encoding 1) or Macintosh (platform 1, encoding 0)
                val name = when {
                    platformID == 3 && encodingID == 1 -> String(nameBytes, Charsets.UTF_16BE)
                    platformID == 1 && encodingID == 0 -> String(nameBytes, Charsets.US_ASCII)
                    else -> continue
                }
                if (name.isNotBlank()) return name.trim()
            } else {
                raf.skipBytes(6) // skip remaining fields
            }
        }
        return null
    }

    /**
     * 解析 cmap 表，检查给定的 Unicode 区间是否至少有部分字符被覆盖。
     * 只要有 >= 50% 的采样点命中就认为支持。
     */
    private fun readCmapAndCheck(raf: RandomAccessFile, cmapOffset: Long, ranges: List<IntRange>): Boolean {
        raf.seek(cmapOffset)
        raf.skipBytes(2) // version
        val numSubtables = raf.readUnsignedShort()

        // Find best subtable (prefer platform 3 encoding 3 or platform 0)
        var bestOffset = -1L
        for (i in 0 until numSubtables) {
            val platformID = raf.readUnsignedShort()
            val encodingID = raf.readUnsignedShort()
            val subtableOffset = raf.readUnsignedInt()

            if (platformID == 0) { // Unicode
                bestOffset = cmapOffset + subtableOffset
                break
            }
            if (platformID == 3 && (encodingID == 1 || encodingID == 10)) {
                bestOffset = cmapOffset + subtableOffset
                // don't break, prefer platform 0 if exists
            }
        }

        if (bestOffset < 0) return false
        return readCmapSubtable(raf, bestOffset, ranges)
    }

    private fun readCmapSubtable(raf: RandomAccessFile, subtableOffset: Long, ranges: List<IntRange>): Boolean {
        raf.seek(subtableOffset)
        val format = raf.readUnsignedShort()

        return when (format) {
            4 -> readCmapFormat4(raf, subtableOffset, ranges)
            12 -> readCmapFormat12(raf, subtableOffset, ranges)
            else -> false
        }
    }

    /**
     * cmap Format 4: BMP characters (U+0000..U+FFFF)
     */
    private fun readCmapFormat4(raf: RandomAccessFile, start: Long, ranges: List<IntRange>): Boolean {
        raf.seek(start)
        raf.skipBytes(2) // format
        val length = raf.readUnsignedShort()
        raf.skipBytes(2) // language
        val segCount = raf.readUnsignedShort() / 2
        raf.skipBytes(6) // searchRange, entrySelector, rangeShift

        val endCodes = IntArray(segCount) { raf.readUnsignedShort() }
        raf.skipBytes(2) // reservedPad
        val startCodes = IntArray(segCount) { raf.readUnsignedShort() }
        val idDeltas = IntArray(segCount) { raf.readUnsignedShort().toShort().toInt() }
        val idRangeOffsetsPos = raf.filePointer
        val idRangeOffsets = IntArray(segCount) { raf.readUnsignedShort() }

        // Check coverage for each range
        for (range in ranges) {
            var total = 0
            var covered = 0
            for (codepoint in range) {
                if (codepoint > 0xFFFF) continue // Format 4 only covers BMP
                if (++total > 50) break // Sample at most 50 codepoints per range
                for (seg in 0 until segCount) {
                    if (codepoint in startCodes[seg]..endCodes[seg]) {
                        if (idRangeOffsets[seg] == 0) {
                            // Character is mapped
                            covered++
                        } else {
                            // Check via offset
                            val glyphIndexAddr = idRangeOffsetsPos + seg * 2 + idRangeOffsets[seg] + (codepoint - startCodes[seg]) * 2
                            raf.seek(glyphIndexAddr)
                            val glyphId = raf.readUnsignedShort()
                            if (glyphId != 0) covered++
                        }
                        break
                    }
                }
            }
            // At least 50% of sampled codepoints must be covered
            if (total > 0 && covered * 2 >= total) return true
        }
        return false
    }

    /**
     * cmap Format 12: Full Unicode coverage (U+0000..U+10FFFF)
     */
    private fun readCmapFormat12(raf: RandomAccessFile, start: Long, ranges: List<IntRange>): Boolean {
        raf.seek(start + 4) // skip format + reserved
        raf.readInt() // length
        raf.readInt() // language
        val numGroups = raf.readInt()

        val groups = Array(numGroups) {
            Triple(raf.readUnsignedInt(), raf.readUnsignedInt(), raf.readUnsignedInt())
        }

        for (range in ranges) {
            var total = 0
            var covered = 0
            for (codepoint in range) {
                if (++total > 50) break
                for ((startCode, endCode, _) in groups) {
                    if (codepoint.toLong() in startCode..endCode) {
                        covered++
                        break
                    }
                }
            }
            if (total > 0 && covered * 2 >= total) return true
        }
        return false
    }

    private fun RandomAccessFile.readUnsignedInt(): Long {
        val b = ByteArray(4)
        readFully(b)
        return ((b[0].toLong() and 0xFF) shl 24) or
                ((b[1].toLong() and 0xFF) shl 16) or
                ((b[2].toLong() and 0xFF) shl 8) or
                (b[3].toLong() and 0xFF)
    }

    private fun RandomAccessFile.skipBytes(n: Int) {
        seek(filePointer + n)
    }
}
