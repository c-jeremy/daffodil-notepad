package app.daffodil.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatImageView

/** A pen colour button: a filled dot, with a ring when it is the active pen. */
class SwatchView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * d }

    var color: Int = 0xFF000000.toInt()
        set(v) { field = v; invalidate() }
    var active: Boolean = false
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        fill.color = color
        ring.color = color
        if (active) {
            canvas.drawCircle(cx, cy, 8.6f * d, ring)
            canvas.drawCircle(cx, cy, 5.6f * d, fill)
        } else {
            canvas.drawCircle(cx, cy, 6.6f * d, fill)
        }
    }
}

/** Shows the current pen thickness as a dot; tapping cycles thin → medium → thick. */
class SizeView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val faint = Paint(Paint.ANTI_ALIAS_FLAG)

    var color: Int = 0xFF000000.toInt()
        set(v) { field = v; invalidate() }
    var level: Int = 1
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val radii = floatArrayOf(1.4f * d, 2.4f * d, 3.8f * d)
        val xs = floatArrayOf(width / 2f - 7.5f * d, width / 2f - 1f * d, width / 2f + 7f * d)
        fill.color = color
        faint.color = (color and 0x00FFFFFF) or 0x38000000
        for (i in 0..2) canvas.drawCircle(xs[i], cy, radii[i], if (i == level) fill else faint)
    }
}

/** 4:3 thumbnail. */
class ThumbView(context: Context, attrs: AttributeSet? = null) : AppCompatImageView(context, attrs) {
    init { clipToOutline = true }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.75f).toInt())
    }
}
