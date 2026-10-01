package com.yjc.click

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FontParser 的 cmap 子表选择测试。
 *
 * 背景：cmap 表里可以有多个子表，解析器需要挑出"覆盖最全"的那个来判断语言支持。
 * 此前遇到 platformID == 0 的子表会立即 break，如果那个子表恰好是 format 0（无文字映射），
 * 就会直接判定"不支持任何语言"，即使后面还有 platform 3/encoding 10 的 format 12 全量 Unicode 子表。
 */
@RunWith(AndroidJUnit4::class)
class FontParserTest {

    @Test
    fun detectsCjkWhenFirstSubtableIsFormat0() {
        val file = writeFakeFont(
            firstPlatformId = 0,
            firstEncodingId = 3,
            firstSubtableFormat = 0,
            secondPlatformId = 3,
            secondEncodingId = 10
        )
        val languages = FontParser.getSupportedLanguages(file)
        assertTrue(
            "cmap 首个 platform 0 子表是 format 0 时，仍应从 format 12 子表识别出 zh，实际=$languages",
            languages.contains("zh")
        )
    }

    @Test
    fun ignoresUnsupportedSubtableFormats() {
        // 两个子表都是 format 0（都不含映射）→ 只能得到兜底的 en，且不应崩溃
        val file = writeFakeFont(
            firstPlatformId = 0,
            firstEncodingId = 3,
            firstSubtableFormat = 0,
            secondPlatformId = 3,
            secondEncodingId = 10,
            secondSubtableFormat = 0
        )
        val languages = FontParser.getSupportedLanguages(file)
        assertTrue("不支持任何语言时应只返回兜底的 en，实际=$languages", languages == listOf("en"))
    }

    /**
     * 造一个最小可解析的 sfnt：只包含 sfnt 头、一张 cmap 表目录项和 cmap 表本身。
     * FontParser 只读这些结构，不校验校验和或其它表，因此这样一个几十字节的假字体就足够。
     */
    private fun writeFakeFont(
        firstPlatformId: Int,
        firstEncodingId: Int,
        firstSubtableFormat: Int,
        secondPlatformId: Int,
        secondEncodingId: Int,
        secondSubtableFormat: Int = 12
    ): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "fake_cmap_font_$firstSubtableFormat$secondSubtableFormat.ttf")

        val sfntHeaderSize = 12
        val tableRecordSize = 16
        val cmapHeaderSize = 4 + 2 * 8
        val firstSubtableSize = 4
        val secondSubtableSize = if (secondSubtableFormat == 12) 28 else 4

        val cmapOffset = sfntHeaderSize + tableRecordSize
        val firstSubtableOffset = cmapHeaderSize
        val secondSubtableOffset = firstSubtableOffset + firstSubtableSize
        val cmapLength = cmapHeaderSize + firstSubtableSize + secondSubtableSize

        val buffer = ByteBuffer.allocate(cmapOffset + cmapLength).order(ByteOrder.BIG_ENDIAN)

        // sfnt 头
        buffer.putInt(0x00010000)
        buffer.putU16(1)   // numTables
        buffer.putU16(16)  // searchRange
        buffer.putU16(0)   // entrySelector
        buffer.putU16(0)   // rangeShift

        // 表目录项：cmap
        buffer.put("cmap".toByteArray(Charsets.US_ASCII))
        buffer.putInt(0)   // checksum
        buffer.putInt(cmapOffset)
        buffer.putInt(cmapLength)

        // cmap 头（偏移相对 cmap 起点）
        buffer.putU16(0)  // version
        buffer.putU16(2)  // numTables
        buffer.putU16(firstPlatformId)
        buffer.putU16(firstEncodingId)
        buffer.putInt(firstSubtableOffset)
        buffer.putU16(secondPlatformId)
        buffer.putU16(secondEncodingId)
        buffer.putInt(secondSubtableOffset)

        // 子表 1
        buffer.putU16(firstSubtableFormat)
        buffer.putU16(0)

        // 子表 2
        buffer.putU16(secondSubtableFormat)
        if (secondSubtableFormat == 12) {
            buffer.putU16(0)            // reserved
            buffer.putInt(28)           // length
            buffer.putInt(0)            // language
            buffer.putInt(1)            // numGroups
            buffer.putInt(0x4E00)       // startCharCode（CJK 统一表意文字）
            buffer.putInt(0x9FFF)       // endCharCode
            buffer.putInt(1)            // startGlyphID
        } else {
            buffer.putU16(0)
        }

        file.writeBytes(buffer.array())
        return file
    }

    private fun ByteBuffer.putU16(value: Int): ByteBuffer = putShort(value.toShort())
}
