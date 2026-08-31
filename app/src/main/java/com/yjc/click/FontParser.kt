package com.yjc.click

import android.graphics.Typeface
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder

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
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val cmapOffset = findTableOffset(raf, "cmap") ?: return false
                val codepoints = getLanguageCodepoints(languageTag)
                readCmapAndCheck(raf, cmapOffset, codepoints)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun getLanguageCodepoints(languageTag: String): List<IntRange> {
        return when (languageTag) {
            "en" -> listOf(0x0020..0x007E) // Basic Latin
            "ja" -> listOf(
                0x3040..0x309F, // Hiragana
                0x30A0..0x30FF, // Katakana
                0x4E00..0x9FFF  // CJK (shared)
            )
            "ko" -> listOf(
                0xAC00..0xD7AF, // Hangul Syllables
                0x4E00..0x9FFF  // CJK (shared)
            )
            "zh" -> listOf(
                0x4E00..0x9FFF,  // CJK Unified Ideographs
                0x3400..0x4DBF  // CJK Extension A (optional but good to check)
            )
            else -> listOf(0x0020..0x007E) // fallback to Basic Latin
        }
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

        // 收集所有子表，优先 format 12 > format 4
        data class SubtableEntry(val offset: Long, val format: Int)
        val candidates = mutableListOf<SubtableEntry>()

        for (i in 0 until numSubtables) {
            val platformID = raf.readUnsignedShort()
            val encodingID = raf.readUnsignedShort()
            val subtableOffset = cmapOffset + raf.readUnsignedInt()

            if (platformID == 0 || (platformID == 3 && (encodingID == 1 || encodingID == 10))) {
                // Read format to decide priority
                val savedPos = raf.filePointer
                raf.seek(subtableOffset)
                val format = raf.readUnsignedShort()
                raf.seek(savedPos)
                candidates.add(SubtableEntry(subtableOffset, format))
            }
        }

        // 优先 format 12（完整Unicode），其次 format 4（BMP）
        val sorted = candidates.sortedByDescending { if (it.format == 12) 1 else 0 }
        for (entry in sorted) {
            if (readCmapSubtable(raf, entry.offset, ranges)) return true
        }
        return false
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

        for (range in ranges) {
            val bmpRange = if (range.first > 0xFFFF) continue else
                maxOf(range.first, 0)..minOf(range.last, 0xFFFF)
            if (bmpRange.isEmpty()) continue

            // 均匀采样100个点
            val sampleCount = minOf(100, bmpRange.last - bmpRange.first + 1)
            val step = maxOf(1, (bmpRange.last - bmpRange.first) / sampleCount)
            var total = 0
            var covered = 0
            var cp = bmpRange.first
            while (cp <= bmpRange.last && total < sampleCount) {
                total++
                for (seg in 0 until segCount) {
                    if (cp in startCodes[seg]..endCodes[seg]) {
                        if (idRangeOffsets[seg] == 0) {
                            covered++
                        } else {
                            val glyphIndexAddr = idRangeOffsetsPos + seg * 2 + idRangeOffsets[seg] + (cp - startCodes[seg]) * 2
                            raf.seek(glyphIndexAddr)
                            val glyphId = raf.readUnsignedShort()
                            if (glyphId != 0) covered++
                        }
                        break
                    }
                }
                cp += step
            }
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
            // 均匀采样100个点
            val rangeSize = range.last.toLong() - range.first.toLong() + 1
            val sampleCount = minOf(100L, rangeSize).toInt()
            val step = maxOf(1L, rangeSize / sampleCount)
            var total = 0
            var covered = 0
            var cp = range.first.toLong()
            while (cp <= range.last && total < sampleCount) {
                total++
                for ((startCode, endCode, _) in groups) {
                    if (cp in startCode..endCode) {
                        covered++
                        break
                    }
                }
                cp += step
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
