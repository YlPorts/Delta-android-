package com.ylports.deltaandroid

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.*
import android.widget.*
import kotlin.math.floor
import kotlin.math.max

/** Native UIKit-style building blocks. Sizes refer to original storyboard points. */
class DeltaUi(val context: Context) {
    fun dp(value: Float) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
    fun dp(value: Int) = dp(value.toFloat())
    val dark get() = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val grouped get() = if (dark) Color.BLACK else Color.rgb(242,242,247)
    val cell get() = if (dark) Color.rgb(28,28,30) else Color.WHITE
    val label get() = if (dark) Color.WHITE else Color.BLACK
    val secondary get() = if (dark) Color.rgb(152,152,160) else Color.rgb(110,110,115)
    val accent get() = OriginalDeltaColors.purple
    fun rounded(color: Int, radius: Float = 10f) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    fun text(value: String, size: Float = 17f, color: Int = label, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    fun link(value: String, color: Int = accent, action: () -> Unit) = text(value, 17f, color).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener { action() }
    }
    fun navigation(title: String, darkBar: Boolean = false, left: View? = null, right: View? = null): FrameLayout {
        val bar = FrameLayout(context).apply {
            setBackgroundColor(if (darkBar) Color.rgb(29,29,31) else grouped)
            minimumHeight = dp(44)
        }
        bar.addView(text(title, 17f, if (darkBar) Color.WHITE else label, true).apply {
            gravity = Gravity.CENTER; maxLines = 1
        }, FrameLayout.LayoutParams(-1, dp(44)).apply { setMargins(dp(95), 0, dp(95), 0) })
        if (left != null) bar.addView(left, FrameLayout.LayoutParams(-2, dp(44), Gravity.START or Gravity.CENTER_VERTICAL))
        if (right != null) bar.addView(right, FrameLayout.LayoutParams(-2, dp(44), Gravity.END or Gravity.CENTER_VERTICAL))
        return bar
    }
    fun section(body: LinearLayout, title: String): LinearLayout {
        body.addView(text(title.uppercase(), 13f, secondary).apply {
            setPadding(dp(16), dp(24), dp(16), dp(8))
        }, LinearLayout.LayoutParams(-1, -2))
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cell)
            clipToOutline = true
            body.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }
    fun footer(body: LinearLayout, value: String) {
        body.addView(text(value, 13f, secondary).apply {
            setPadding(dp(16), dp(7), dp(16), dp(4))
        })
    }
    fun row(group: LinearLayout, title: String, detail: String = "", enabled: Boolean = true,
            accessory: View? = null, action: (() -> Unit)? = null): View {
        if (group.childCount > 0) group.addView(View(context).apply {
            setBackgroundColor(if (dark) Color.rgb(56,56,58) else Color.rgb(216,216,220))
        }, LinearLayout.LayoutParams(-1, max(1, dp(0.5f))).apply { leftMargin = dp(16) })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            minimumHeight = dp(51)
            isEnabled = enabled
            if (action != null && enabled) setOnClickListener { action() }
        }
        row.addView(text(title, 17f, if (enabled) label else secondary), LinearLayout.LayoutParams(0, dp(51), 1f))
        if (detail.isNotEmpty()) row.addView(text(detail, 17f, secondary).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(190)
        }, LinearLayout.LayoutParams(-2, dp(51)))
        if (accessory != null) row.addView(accessory, LinearLayout.LayoutParams(-2,-2))
        else if (action != null) row.addView(text("›", 25f, secondary).apply {
            setPadding(dp(10),0,0,0)
        }, LinearLayout.LayoutParams(-2, dp(51)))
        group.addView(row, LinearLayout.LayoutParams(-1,-2))
        return row
    }
    fun toggle(checked: Boolean, onChange: (Boolean) -> Unit): View = object : View(context) {
        private var value = checked
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init {
            isClickable = true
            contentDescription = if (value) "On" else "Off"
            setOnClickListener {
                value = !value; contentDescription = if (value) "On" else "Off"
                onChange(value); invalidate()
                sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            }
        }
        override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(dp(51),dp(31)) }
        override fun onDraw(canvas: Canvas) {
            paint.color = if (value) accent else if (dark) Color.rgb(58,58,60) else Color.rgb(220,220,224)
            canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),height/2f,height/2f,paint)
            paint.color = Color.WHITE
            val radius = height/2f - dp(2)
            canvas.drawCircle(if (value) width-height/2f else height/2f,height/2f,radius,paint)
        }
        override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.Switch"; info.isCheckable = true; info.isChecked = value
        }
    }
    fun sheet(title: String, body: View, close: String = "Done", onClose: (() -> Unit)? = null): Dialog {
        val dialog = Dialog(context)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(grouped)
        }
        panel.addView(navigation(title, right = link(close) { dialog.dismiss(); onClose?.invoke() }))
        val scroll = ScrollView(context).apply { addView(body) }
        panel.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(-1, (context.resources.displayMetrics.heightPixels * .80f).toInt())
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.35f)
        }
        dialog.show()
        dialog.window?.setLayout(-1,(context.resources.displayMetrics.heightPixels*.80f).toInt())
        return dialog
    }
    fun icon(resource: Int, size: Int, color: Int? = null) = ImageView(context).apply {
        setImageResource(resource)
        if (color != null) setColorFilter(color, PorterDuff.Mode.SRC_IN)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        minimumWidth = dp(size); minimumHeight = dp(size)
    }
    fun settingsIcon(): View {
        val bitmap = context.assets.open("ios/settings.pdf").use {
            SkinPackage.renderPdf(context, it.readBytes(), 24f,24f)
        }
        return ImageView(context).apply {
            setImageBitmap(bitmap); setColorFilter(OriginalDeltaColors.purple, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(16),dp(10),dp(16),dp(10))
            minimumWidth = dp(56)
            contentDescription = "Settings"
        }
    }
}

/** Port of GridCollectionViewLayout's equal-spacing formula, including insets. */
class DeltaGrid(context: Context, private val itemWidth: Int = 90,
                private val minSpacing: Int = 12, private val lineSpacing: Int = 20,
                private val verticalInset: Int = 20, private val distributeSingleRow: Boolean = false) : ViewGroup(context) {
    private val density = resources.displayMetrics.density
    private fun px(v: Int) = (v*density+.5f).toInt()
    private var columns = 1
    private var gap = 0f
    private var rows = intArrayOf()
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val tile = px(itemWidth)
        columns = floor((width-px(minSpacing)).toDouble()/(tile+px(minSpacing))).toInt().coerceAtLeast(1)
        if (distributeSingleRow && childCount in 1..columns) columns = childCount
        gap = (width-columns*tile).toFloat()/(columns+1)
        rows = IntArray((childCount+columns-1)/columns)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(MeasureSpec.makeMeasureSpec(tile, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            rows[i/columns] = max(rows[i/columns],child.measuredHeight)
        }
        val height = px(verticalInset)*2 + rows.sum() + px(lineSpacing)*max(0,rows.size-1)
        setMeasuredDimension(width,resolveSize(height,h))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = px(verticalInset)
        for (i in rows.indices) {
            for (column in 0 until columns) {
                val index = i*columns+column
                if (index >= childCount) break
                val child = getChildAt(index)
                val x = (gap+column*(px(itemWidth)+gap)+.5f).toInt()
                child.layout(x,y,x+child.measuredWidth,y+child.measuredHeight)
            }
            y += rows[i]+px(lineSpacing)
        }
    }
    override fun generateDefaultLayoutParams() = LayoutParams(-2,-2)
}
