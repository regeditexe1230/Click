package com.yjc.click.ui.program

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * turnstile 的投影：一页绕竖轴转 [thetaDeg] 度之后，屏幕上 4 个角落的落点（左上、右上、右下、左下）。
 *
 * 与官方 `perspective(P) rotateY(θ)` 逐字对应：点 (x,·) 到轴的距离 d = x − originX，
 * 深度 d·sinθ（正值 = 转进屏幕里、离观察者更远），缩放 = P / (P + d·sinθ)。
 * θ = 0（或没给透视）时返回 null —— 调用方直接原样画，静止态的绘制路径和没有这一层一样。
 *
 * 单独成一个文件：这是纯数学，`TurnstileProjectionTest` 要在 JVM 上直接跑它；
 * 混在 ProgramScreen.kt 里会连 Compose 一起加载，普通单元测试跑不起来。
 */
internal fun turnstileQuad(
    thetaDeg: Float,
    originX: Float,
    originY: Float,
    perspective: Float,
    width: Float,
    height: Float,
): FloatArray? {
    if (abs(thetaDeg) < 0.01f || perspective <= 0f) return null
    val t = Math.toRadians(thetaDeg.toDouble())
    val sinT = sin(t).toFloat()
    val cosT = cos(t).toFloat()
    // 退化护栏：分母 = P + d·sinθ（d = x − originX）沿 x 线性，所以两端取小就够 —— 任一端 ≤ 0
    // 就说明那个点已经跑到相机平面背后，射影矩阵会把画面翻折。
    // 官方的不退化条件是「屏宽 < P/|sinθ| − 40dp」（480dp 的手机 vs 743dp 上限，安全），
    // 但平板 / 折叠屏展开 / 自由窗口会撞上这条线；另外 1440px 宽配 600px 这种「把 CSS px 当 px 用」
    // 的写法，在 −50° 时右端分母 600 − 1560·sin50 = −595 已经是负的 —— 宁可退回原样绘制。
    val denomAtLeft = perspective + (0f - originX) * sinT
    val denomAtRight = perspective + (width - originX) * sinT
    if (minOf(denomAtLeft, denomAtRight) <= 0f) return null
    fun scaleAt(x: Float): Float = perspective / (perspective + (x - originX) * sinT)
    fun px(x: Float): Float = originX + (x - originX) * cosT * scaleAt(x)
    fun py(x: Float, y: Float): Float = originY + (y - originY) * scaleAt(x)
    return floatArrayOf(
        px(0f), py(0f, 0f),
        px(width), py(width, 0f),
        px(width), py(width, height),
        px(0f), py(0f, height),
    )
}
