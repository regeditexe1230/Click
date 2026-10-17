package com.yjc.click.ui.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * turnstile 投影的 JVM 单元测试（纯数学，不依赖 Compose / 设备）。
 *
 * 屏幕口径按验收机：1440 x 3200 px、密度 3 ⇒ −40dp = −120px、600dp = 1800px。
 * 头号判据是**第 1 帧（θ=80°）左右边缘的高度比 = 1.7393**（与 flip 官方逐位一致）；
 * ≈1.0 或左右对称压窄，就说明转轴被取在了页面中心，而不是屏幕左外侧 40dp。
 */
class TurnstileProjectionTest {

    private val width = 1440f
    private val height = 3200f

    /** TURNSTILE_ORIGIN_X = (−40).dp，本机密度 3 ⇒ −120px */
    private val axisX = -120f

    /** 轴在屏幕竖直中线 */
    private val axisY = height / 2f

    /** TURNSTILE_PERSPECTIVE_IN = 600.dp，本机 1800px（CSS px ≡ dp，1440px/3 = 480dp 是 flip 手机视口） */
    private val perspectiveIn = 1800f

    /** 四角顺序：左上、右上、右下、左下 ⇒ 左边缘高 = y[7]−y[1]，右边缘高 = y[5]−y[3] */
    private fun edgeHeights(thetaDeg: Float): Pair<Float, Float> {
        val quad = turnstileQuad(thetaDeg, axisX, axisY, perspectiveIn, width, height)
        assertNotNull("θ=$thetaDeg 不该返回 null", quad)
        val left = quad!![7] - quad[1]
        val right = quad[5] - quad[3]
        return left to right
    }

    @Test
    fun thetaZeroIsIdentity() {
        assertNull(turnstileQuad(0f, axisX, axisY, perspectiveIn, width, height))
    }

    @Test
    fun firstFrameEdgeRatioIs17393() {
        val (left, right) = edgeHeights(80f)
        assertEquals(1.7393f, left / right, 0.005f)
        assertEquals(0.93839f, left / height, 0.001f)
        assertEquals(0.53952f, right / height, 0.001f)
        // 左高右矮才算有透视：等高 = 轴取成了页面中心
        assertTrue("左边缘必须比右边缘高（轴在屏幕左外侧）", left > right)
    }

    @Test
    fun nonPositivePerspectiveDrawsNothing() {
        assertNull(turnstileQuad(80f, axisX, axisY, 0f, width, height))
        assertNull(turnstileQuad(80f, axisX, axisY, -100f, width, height))
    }

    @Test
    fun degenerateDenominatorReturnsNull() {
        // 1440px 宽配 600px 透视、−50°：右端 d·sinθ = 1560·sin50 ≈ 1195 > 600 ⇒ 分母过零、画面翻折
        assertNull(turnstileQuad(-50f, axisX, axisY, 600f, width, height))
        // 正确的手机口径（600dp = 1800px）在 −50° 下安全
        assertNotNull(turnstileQuad(-50f, axisX, axisY, perspectiveIn, width, height))
    }

    @Test
    fun rightEdgeFoldsIntoLeftHalf() {
        val quad = turnstileQuad(80f, axisX, axisY, perspectiveIn, width, height)!!
        // 右上角被转进屏幕 ⇒ 必须明显左移；左上角仍在屏幕内
        assertTrue("右上角 x 应明显小于半屏宽", quad[2] < width / 2f)
        assertTrue("左上角 x 不应飞出屏幕左侧太多", quad[0] > -200f)
    }
}
