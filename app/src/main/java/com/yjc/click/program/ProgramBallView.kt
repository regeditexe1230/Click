package com.yjc.click.program

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * 程序悬浮球：一个半透明圆底 + 白色描边 + 中间的小图标。
 *
 * 只负责画，不处理触摸 —— 拖动/单击/双击/长按都在 ProgramService 里，因为它需要
 * WindowManager 的位置和整个程序的运行状态。
 */
class ProgramBallView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    /** 圆底颜色（节点 color）。 */
    var ballColor: Int = PROGRAM_COLORS[0]
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 中间的小图标，按节点类型区分。 */
    var icon: ProgramIcon = ProgramIcon.CLICK
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 等用户点击时稍微亮一点，让用户知道「现在轮到你了」。 */
    var waiting: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2.5f * density
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val iconPath = Path()
    private val center = FloatArray(2)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        center[0] = w / 2f
        center[1] = h / 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f - strokePaint.strokeWidth
        if (radius <= 0f) return

        // 半透明圆底：既看得见，又不完全挡住下面的内容
        fillPaint.color = ballColor
        fillPaint.alpha = if (waiting) 235 else 190
        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius, strokePaint)
        drawIcon(canvas, cx, cy, radius * 0.5f)
    }

    /** 图标都画在半径 r 的圆内，r 约为球的半径一半。 */
    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        when (icon) {
            // 点击：一个实心圆点（就是「点」）
            ProgramIcon.CLICK -> canvas.drawCircle(cx, cy, r * 0.55f, iconPaint)

            // 滑动：一根带箭头的斜线
            ProgramIcon.SWIPE -> {
                iconPath.reset()
                iconPath.moveTo(cx - r * 0.8f, cy + r * 0.8f)
                iconPath.lineTo(cx + r * 0.5f, cy - r * 0.5f)
                canvas.drawPath(iconPath, iconStrokePaint)
                iconPath.reset()
                iconPath.moveTo(cx + r * 0.05f, cy - r * 0.8f)
                iconPath.lineTo(cx + r * 0.85f, cy - r * 0.85f)
                iconPath.lineTo(cx + r * 0.8f, cy - r * 0.05f)
                iconPath.close()
                canvas.drawPath(iconPath, iconPaint)
            }

            // 延时：一个沙漏
            ProgramIcon.DELAY -> {
                iconPath.reset()
                iconPath.moveTo(cx - r * 0.6f, cy - r * 0.85f)
                iconPath.lineTo(cx + r * 0.6f, cy - r * 0.85f)
                iconPath.lineTo(cx - r * 0.6f, cy + r * 0.85f)
                iconPath.lineTo(cx + r * 0.6f, cy + r * 0.85f)
                iconPath.close()
                canvas.drawPath(iconPath, iconStrokePaint)
            }

            // 等待：一个暂停符号（两根竖条）
            ProgramIcon.WAIT -> {
                val bar = r * 0.26f
                val gap = r * 0.16f
                canvas.drawRoundRect(cx - gap - bar, cy - r * 0.8f, cx - gap, cy + r * 0.8f, bar, bar, iconPaint)
                canvas.drawRoundRect(cx + gap, cy - r * 0.8f, cx + gap + bar, cy + r * 0.8f, bar, bar, iconPaint)
            }

            // 循环：一个带箭头的圈
            ProgramIcon.LOOP -> {
                canvas.drawCircle(cx, cy, r * 0.65f, iconStrokePaint)
                iconPath.reset()
                iconPath.moveTo(cx + r * 0.2f, cy - r * 0.95f)
                iconPath.lineTo(cx + r * 0.95f, cy - r * 0.6f)
                iconPath.lineTo(cx + r * 0.3f, cy - r * 0.2f)
                iconPath.close()
                canvas.drawPath(iconPath, iconPaint)
            }

            // 开始：一个三角形播放符
            ProgramIcon.START -> {
                iconPath.reset()
                iconPath.moveTo(cx - r * 0.45f, cy - r * 0.75f)
                iconPath.lineTo(cx + r * 0.8f, cy)
                iconPath.lineTo(cx - r * 0.45f, cy + r * 0.75f)
                iconPath.close()
                canvas.drawPath(iconPath, iconPaint)
            }
        }
    }
}

/** 球中间画什么，由节点类型决定。 */
enum class ProgramIcon { START, CLICK, SWIPE, DELAY, WAIT, LOOP }

/** 节点类型 → 图标。 */
fun ProgramNodeType.toIcon(): ProgramIcon = when (this) {
    ProgramNodeType.START -> ProgramIcon.START
    ProgramNodeType.CLICK -> ProgramIcon.CLICK
    ProgramNodeType.SWIPE -> ProgramIcon.SWIPE
    ProgramNodeType.DELAY -> ProgramIcon.DELAY
    ProgramNodeType.WAIT -> ProgramIcon.WAIT
    ProgramNodeType.LOOP -> ProgramIcon.LOOP
}
