package local.chessbridge

import android.graphics.Rect
import kotlin.math.roundToInt
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

internal class PracticeOverlay(private val service: ChessService) {
    companion object { const val TITLE = "Xiangqi practice controls" }

    private val manager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var panel: LinearLayout? = null
    private var caption: TextView? = null
    private var red: TextView? = null
    private var black: TextView? = null
    private var selectedSide: Boolean? = null
    private var picker: android.widget.FrameLayout? = null
    private var panelX = 0
    private var panelY = 0

    private val refresh = object : Runnable {
        override fun run() {
            try {
                if (!service.canShowOverlay()) {
                    closePicker()
                    hide()
                } else if (picker == null) show()
            } catch (error: WindowManager.BadTokenException) {
                // Android can revoke the window token as the service disconnects.
                android.util.Log.w("ChessBridge", "Overlay window token unavailable", error)
            }
            handler.postDelayed(this, 350)
        }
    }

    fun start() { handler.post(refresh) }
    fun close() {
        handler.removeCallbacks(refresh)
        closePicker()
        hide()
    }

    private fun dp(value: Int) = (value * service.resources.displayMetrics.density + 0.5f).toInt()

    private fun show() {
        if (panel == null) {
            val row = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(4), 0)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(30, 29, 26))
                    cornerRadius = dp(18).toFloat()
                }
            }
            caption = TextView(service).apply {
                setTextColor(Color.rgb(248, 239, 224))
                textSize = 12f
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                row.addView(this, LinearLayout.LayoutParams(dp(88), dp(48)))
            }
            fun action(label: String, description: String, color: Int, run: () -> Unit) =
                TextView(service).apply {
                    text = label
                    contentDescription = description
                    gravity = Gravity.CENTER
                    setTextColor(color)
                    textSize = 14f
                    isFocusable = true
                    setOnClickListener { run() }
                    row.addView(this, LinearLayout.LayoutParams(dp(48), dp(48)))
                }
            action("定位", "手动定位棋盘", Color.WHITE) {
                selectedSide = null
                service.locateBoardManually()
            }
            red = action("执红", "确认红方待走并接管", Color.rgb(255, 186, 170)) { select(true) }
            black = action("执黑", "确认黑方待走并接管", Color.rgb(231, 220, 202)) { select(false) }
            action("停止", "停止自动落子", Color.WHITE) { selectedSide = null; service.stop("已手动停止") }
            val params = WindowManager.LayoutParams(-2, -2,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                val panelWidth = row.paddingLeft + row.paddingRight +
                    (0 until row.childCount).sumOf { row.getChildAt(it).layoutParams.width }
                x = panelX.coerceIn(-horizontalLimit(panelWidth), horizontalLimit(panelWidth))
                y = panelY.coerceIn(0, verticalLimit(dp(48)))
                setTitle(TITLE)
            }
            manager.addView(row, params)
            enableDrag(row, checkNotNull(caption), params)
            panel = row
        }
        caption?.text = when (selectedSide) {
            true -> "确认轮到红方？再点执红"
            false -> "确认轮到黑方？再点执黑"
            null -> ChessService.status
        }
        for (button in listOf(red, black)) {
            button?.isEnabled = !service.practiceRunning
            button?.alpha = if (service.practiceRunning) 0.45f else 1f
        }
    }

    private fun usableBounds(): Rect {
        val metrics = manager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return Rect(metrics.bounds).apply {
            left += insets.left
            top += insets.top
            right -= insets.right
            bottom -= insets.bottom
        }
    }

    private fun horizontalLimit(width: Int) = ((usableBounds().width() - width) / 2).coerceAtLeast(0)
    private fun verticalLimit(height: Int) = (usableBounds().height() - height).coerceAtLeast(0)

    private fun enableDrag(row: View, handle: TextView, params: WindowManager.LayoutParams) {
        var dragging = false
        var startX = 0
        var startY = 0
        var fingerX = 0f
        var fingerY = 0f
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        val longPress = Runnable {
            if (row.isAttachedToWindow) {
                dragging = true
                handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    startX = params.x
                    startY = params.y
                    fingerX = event.rawX
                    fingerY = event.rawY
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - fingerX
                    val dy = event.rawY - fingerY
                    if (dragging && row.isAttachedToWindow) {
                        val limit = horizontalLimit(row.width)
                        panelX = (startX + dx.roundToInt()).coerceIn(-limit, limit)
                        panelY = (startY + dy.roundToInt()).coerceIn(0, verticalLimit(row.height))
                        params.x = panelX
                        params.y = panelY
                        manager.updateViewLayout(row, params)
                    } else if (dx * dx + dy * dy > slop.toFloat() * slop) {
                        handler.removeCallbacks(longPress)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    handler.removeCallbacks(longPress)
                    dragging = false
                }
            }
            true
        }
    }

    fun position(bounds: Rect, saved: Corners?, done: (Corners) -> Unit) {
        closePicker()
        hide()
        val layout = android.widget.FrameLayout(service)
        val grid = object : View(service) {
            private val board = RectF()
            private val limits = RectF()
            private val point = IntArray(2)
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val handle = dp(25).toFloat()
            private var corner = -1
            private var lastX = 0f
            private var lastY = 0f

            private fun prepare() {
                getLocationOnScreen(point)
                limits.set((bounds.left - point[0]).coerceAtLeast(0).toFloat(),
                    (bounds.top - point[1]).coerceAtLeast(dp(58)).toFloat(),
                    (bounds.right - point[0]).coerceAtMost(width).toFloat(),
                    (bounds.bottom - point[1]).coerceAtMost(height).toFloat())
                if (!board.isEmpty || limits.width() < dp(96) || limits.height() < dp(96)) return
                if (saved != null) {
                    board.set((bounds.left + saved.left - point[0]).toFloat(), (bounds.top + saved.top - point[1]).toFloat(),
                        (bounds.left + saved.right - point[0]).toFloat(), (bounds.top + saved.bottom - point[1]).toFloat())
                } else {
                    val w = minOf(limits.width() * 0.84f, limits.height() * 0.65f)
                    val h = w * 9 / 8
                    board.set(limits.centerX() - w / 2, limits.centerY() - h / 2,
                        limits.centerX() + w / 2, limits.centerY() + h / 2)
                }
            }

            override fun onDraw(canvas: Canvas) {
                prepare()
                if (board.isEmpty) return
                paint.color = Color.argb(210, 0, 240, 255)
                paint.strokeWidth = dp(1).toFloat()
                for (file in 0..8) {
                    val x = board.left + file * board.width() / 8
                    canvas.drawLine(x, board.top, x, board.bottom, paint)
                }
                for (rank in 0..9) {
                    val y = board.top + rank * board.height() / 9
                    canvas.drawLine(board.left, y, board.right, y, paint)
                }
                for (x in listOf(board.left, board.right)) for (y in listOf(board.top, board.bottom)) {
                    canvas.drawCircle(x, y, dp(8).toFloat(), paint)
                }
            }

            fun selection(): Corners? {
                if (board.isEmpty || !limits.contains(board)) return null
                getLocationOnScreen(point)
                val x = point[0] - bounds.left
                val y = point[1] - bounds.top
                return Corners((board.left + x).roundToInt(), (board.top + y).roundToInt(),
                    (board.right + x).roundToInt(), (board.bottom + y).roundToInt())
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (board.isEmpty) return true
                val x = event.x
                val y = event.y
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        corner = when {
                            near(x, y, board.left, board.top) -> 0
                            near(x, y, board.right, board.top) -> 1
                            near(x, y, board.left, board.bottom) -> 2
                            near(x, y, board.right, board.bottom) -> 3
                            board.contains(x, y) -> 4
                            else -> -1
                        }
                    }
                    MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                        val dx = x - lastX
                        val dy = y - lastY
                        val minimum = dp(96).toFloat()
                        if (corner == 4) board.offset(dx.coerceIn(limits.left - board.left, limits.right - board.right),
                            dy.coerceIn(limits.top - board.top, limits.bottom - board.bottom))
                        else if (corner in 0..3) {
                            if (corner % 2 == 0) board.left = (board.left + dx).coerceIn(limits.left, board.right - minimum)
                            else board.right = (board.right + dx).coerceIn(board.left + minimum, limits.right)
                            if (corner < 2) board.top = (board.top + dy).coerceIn(limits.top, board.bottom - minimum)
                            else board.bottom = (board.bottom + dy).coerceIn(board.top + minimum, limits.bottom)
                        }
                        invalidate()
                    }
                }
                lastX = x
                lastY = y
                return true
            }

            private fun near(x: Float, y: Float, cx: Float, cy: Float) =
                kotlin.math.abs(x - cx) <= handle && kotlin.math.abs(y - cy) <= handle
        }
        layout.addView(grid, android.widget.FrameLayout.LayoutParams(-1, -1))
        val bar = LinearLayout(service).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(30, 29, 26))
        }
        fun control(text: String, width: Int, click: (() -> Unit)? = null) {
            bar.addView(TextView(service).apply {
                this.text = text
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                if (click != null) setOnClickListener { click() }
            }, LinearLayout.LayoutParams(dp(width), dp(48)))
        }
        control("拖中间移动，拉角点缩放", 164)
        control("取消", 53) { closePicker() }
        control("确认", 53) {
            grid.selection()?.let {
                closePicker()
                done(it)
            }
        }
        layout.addView(bar, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { setTitle(TITLE) }
        try {
            manager.addView(layout, params)
            picker = layout
        } catch (_: WindowManager.BadTokenException) {
            ChessService.status = "定位窗口无法打开，请重试"
        }
    }

    private fun closePicker() {
        picker?.let(manager::removeViewImmediate)
        picker = null
    }

    private fun select(red: Boolean) {
        if (selectedSide == red) {
            selectedSide = null
            service.arm(red)
        } else selectedSide = red
    }

    private fun hide() {
        selectedSide = null
        panel?.let(manager::removeViewImmediate)
        panel = null
        caption = null
        red = null
        black = null
    }
}
