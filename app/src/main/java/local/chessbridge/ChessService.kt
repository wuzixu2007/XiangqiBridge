package local.chessbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class ChessService : AccessibilityService() {
    companion object {
        @Volatile internal var instance: ChessService? = null
        @Volatile internal var status = "等待无障碍服务"
    }

    private data class Target(val id: Int, val bounds: Rect, val app: String)
    private data class Selection(val target: Target, val corners: Corners)
    private class Game(val target: Target, val red: Boolean, var layout: Calibration?) {
        var candidate: Corners? = null
        var previous: String? = null
        var board: Board? = null
        var expected: Board? = null
        var readyMove: String? = null
        var thinking = false
        var touching = false
        var deadline = 0L
        var lastValid = SystemClock.elapsedRealtime()
        var mismatchSince: Long? = null
    }

    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val engineWorker = Executors.newSingleThreadExecutor()
    private val engine = lazy { PhoneEngine(applicationContext) }
    private val recognizer = lazy { OnnxBoard(applicationContext) }
    private var overlay: PracticeOverlay? = null
    private var job: ScheduledFuture<*>? = null
    private var captureToken = 0
    private var capturing = false
    @Volatile private var gesturePending = false
    @Volatile private var game: Game? = null
    @Volatile private var selection: Selection? = null
    @Volatile private var suggestion: Selection? = null
    internal val practiceRunning get() = game != null

    override fun onServiceConnected() {
        instance = this
        status = "无障碍服务已启用"
        if (job == null) job = worker.scheduleWithFixedDelay({
            try { poll() } catch (error: Exception) { fail("识别失败", error) }
        }, 0, 360, TimeUnit.MILLISECONDS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { stop("无障碍服务中断") }

    override fun onDestroy() {
        closeAssistant()
        if (instance === this) instance = null
        job?.cancel(false)
        engineWorker.shutdownNow()
        worker.execute { if (recognizer.isInitialized()) recognizer.value.close() }
        worker.shutdown()
        super.onDestroy()
    }

    internal fun startAssistant() {
        if (overlay != null) return
        overlay = PracticeOverlay(this).also { it.start() }
        status = "轮到你时选择执棋颜色"
    }

    internal fun closeAssistant() {
        overlay?.close()
        overlay = null
        selection = null
        suggestion = null
        stop("助手已关闭")
    }

    internal fun canShowOverlay() = !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    internal fun locateBoardManually() {
        if (practiceRunning || gesturePending) {
            status = "请先停止走棋，再重新定位"
            return
        }
        val active = target() ?: run {
            status = "请让棋盘完整显示后再定位"
            return
        }
        val saved = selection?.takeIf { it.target == active } ?: suggestion?.takeIf { it.target == active }
        overlay?.position(active.bounds, saved?.corners) { corners ->
            if (target() != active) status = "棋盘窗口已变化，请重新定位"
            else {
                selection = Selection(active, corners)
                status = "定位完成，轮到你时选择执棋颜色"
            }
        }
    }

    internal fun arm(red: Boolean) {
        if (game != null || gesturePending) return
        val active = target()
        if (active == null) {
            status = "请先打开棋局"
            return
        }
        val corners = selection?.takeIf { it.target == active }?.corners
        val layout = corners?.let { Calibration(active.bounds.width(), active.bounds.height(), red, it) }
        status = if (layout == null) "正在自动定位棋盘" else "正在识别棋盘"
        game = Game(active, red, layout)
    }

    internal fun stop(message: String) {
        val wasRunning = game != null
        game = null
        status = message
        if (wasRunning) Log.i("ChessBridge", message)
        if (engine.isInitialized()) engine.value.close()
    }

    private fun fail(message: String, error: Throwable) {
        Log.w("ChessBridge", message, error)
        stop(message)
    }

    private fun target(): Target? {
        if (!canShowOverlay()) return null
        val window = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive } ?: return null
        val app = window.root?.packageName?.toString() ?: return null
        if (app == packageName) return null
        val bounds = Rect().also(window::getBoundsInScreen)
        return if (bounds.isEmpty) null else Target(window.id, bounds, app)
    }

    private fun unobstructed(target: Target, corners: Corners, taps: List<Pair<Int, Int>> = emptyList()): Boolean {
        if (target() != target) return false
        val windowList = windows
        val targetLayer = windowList.firstOrNull { it.id == target.id }?.layer ?: return false
        val area = corners.run { Rect(left, top, right + 1, bottom + 1) }
        area.offset(target.bounds.left, target.bounds.top)
        val file = area.width() / 8
        return windowList.none { window ->
            if (window.id == target.id || window.layer <= targetLayer) return@none false
            val obstruction = Rect().also(window::getBoundsInScreen)
            if (!Rect.intersects(area, obstruction)) return@none false
            // A collapsed system side handle can overlap the outer file. Its
            // touchable region still blocks any move that would hit the handle.
            val edgeHandle = window.type == -1 && obstruction.width() <= file &&
                (obstruction.right <= area.left + file / 2 || obstruction.left >= area.right - file / 2)
            !edgeHandle || taps.any { (x, y) -> obstruction.contains(x, y) }
        }
    }

    private fun poll() {
        val current = game ?: return
        if (capturing || current.touching) return
        val layout = current.layout
        if (target() != current.target || (layout != null && !unobstructed(current.target, layout.corners))) {
            if (game === current) stop("棋盘窗口已切换或被遮挡")
            return
        }
        capturing = true
        val token = ++captureToken
        worker.schedule({
            if (capturing && token == captureToken) {
                capturing = false
                captureToken++
                observe(current, null)
            }
        }, 1200, TimeUnit.MILLISECONDS)
        try {
            takeScreenshotOfWindow(current.target.id, worker, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    if (token != captureToken) { buffer.close(); return }
                    capturing = false
                    try {
                        val bitmap = try {
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        } finally { buffer.close() }
                        try { observe(current, bitmap) } finally { bitmap?.recycle() }
                    } catch (error: Exception) { if (game === current) fail("棋盘识别失败", error) }
                    catch (error: LinkageError) { if (game === current) fail("无法加载手机识别引擎", error) }
                }

                override fun onFailure(errorCode: Int) {
                    if (token != captureToken) return
                    capturing = false
                    observe(current, null)
                }
            })
        } catch (error: Exception) {
            capturing = false
            throw error
        }
    }

    private fun observe(current: Game, bitmap: Bitmap?) {
        if (game !== current) return
        val existing = current.layout
        val width = current.target.bounds.width()
        val height = current.target.bounds.height()
        // Android may capture at a lower resolution than accessibility gesture coordinates.
        val xScale = bitmap?.let { it.width.toFloat() / width } ?: 0f
        val yScale = bitmap?.let { it.height.toFloat() / height } ?: 0f
        val aligned = bitmap != null && abs(xScale - yScale) <= 1f / minOf(width, height)
        val detected = if (existing == null && bitmap != null && aligned) {
            recognizer.value.locate(bitmap)?.scaled(1 / xScale, 1 / yScale)
        } else null
        if (game !== current) return
        if (detected != null) {
            if (!unobstructed(current.target, detected)) {
                stop("棋盘窗口已切换或被遮挡")
                return
            }
            suggestion = Selection(current.target, detected)
        }
        val layout = existing ?: detected?.let { Calibration(width, height, current.red, it) }
        val cells = if (bitmap != null && layout != null && aligned) {
            val sample = Calibration(bitmap.width, bitmap.height, layout.red, layout.corners.scaled(xScale, yScale))
            recognizer.value.read(bitmap, sample)?.cells
        } else null
        if (game !== current) return
        val now = SystemClock.elapsedRealtime()
        if (current.expected != null && now > current.deadline) {
            stop("未能确认落子或对方回着")
            return
        }
        if (cells == null) {
            current.previous = null
            if (now - current.lastValid > 4500) stop(if (existing == null)
                "自动定位未通过，请点定位微调" else "无法可靠识别当前棋盘，请检查定位和遮挡")
            return
        }
        val candidate = current.candidate
        val gridChanged = detected != null && (candidate == null || !sameGrid(candidate, detected))
        if (current.previous != cells || gridChanged) {
            current.previous = cells
            current.candidate = detected
            if (now - current.lastValid > 4500) stop("棋盘持续变化，识别已暂停")
            return
        }

        current.lastValid = now
        checkNotNull(layout)
        val before = current.board
        val expected = current.expected
        val next = when {
            before == null -> Board(cells, layout.side).takeIf(XiangqiRules::validSeed)
            expected != null && cells == before.cells -> return
            expected != null -> XiangqiRules.reconcile(expected, cells)
            else -> XiangqiRules.reconcile(before, cells)?.takeIf { it == before || before.side != layout.side }
        }
        if (next == null) {
            val since = current.mismatchSince ?: now.also { current.mismatchSince = it }
            if (now - since > 2600) stop("当前画面无法用合法走法解释，请重新确认棋盘")
            return
        }
        current.mismatchSince = null
        if (existing == null) {
            current.layout = layout
            Log.i("ChessBridge", "Auto-located board: ${layout.corners}, ${next.fen()}")
        }
        current.board = next
        if (expected != null) {
            current.expected = null
            Log.i("ChessBridge", "Local move confirmed: ${next.fen()}")
        }
        if (next.side != layout.side) {
            status = "等待对方走棋"
            return
        }
        val move = current.readyMove
        if (move != null) {
            current.readyMove = null
            sendMove(current, layout, next, move)
        } else if (!current.thinking) think(current, next)
    }

    private fun sameGrid(first: Corners, second: Corners): Boolean {
        val tolerance = (first.right - first.left) / 32f
        return abs(first.left - second.left) <= tolerance && abs(first.right - second.right) <= tolerance &&
            abs(first.top - second.top) <= tolerance && abs(first.bottom - second.bottom) <= tolerance
    }

    private fun think(current: Game, board: Board) {
        current.thinking = true
        status = "手机算棋中"
        val started = SystemClock.elapsedRealtime()
        engineWorker.execute {
            val result = runCatching { if (game === current) engine.value.choose(board) else null }
            if (!worker.isShutdown) worker.execute {
                if (game !== current) return@execute
                current.thinking = false
                val move = result.getOrNull()
                if (move == null || XiangqiRules.apply(board, move) == null) {
                    stop(if (result.isFailure) "手机引擎无法运行" else "本方没有可执行的合法走法")
                } else {
                    current.readyMove = move
                    Log.i("ChessBridge", "Local bestmove $move in ${SystemClock.elapsedRealtime() - started} ms")
                }
            }
        }
        worker.schedule({
            if (game === current && current.thinking && current.board == board) stop("手机算棋超时")
        }, 12, TimeUnit.SECONDS)
    }

    private fun sendMove(current: Game, layout: Calibration, before: Board, move: String) {
        val expected = XiangqiRules.apply(before, move) ?: return stop("引擎走法无效")
        current.touching = true
        mainExecutor.execute {
            if (game !== current) return@execute
            val bounds = current.target.bounds
            val taps = (0..1).map { i ->
                val (x, y) = layout.point(square(move[i * 2], move[i * 2 + 1]))
                (bounds.left + x) to (bounds.top + y)
            }
            if (!unobstructed(current.target, layout.corners, taps)) {
                stop("落子前棋盘窗口已变化或被遮挡")
                return@execute
            }
            val gesture = GestureDescription.Builder()
            for ((i, tap) in taps.withIndex()) {
                val (x, y) = tap
                val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                gesture.addStroke(GestureDescription.StrokeDescription(path, i * 130L, 45))
            }
            gesturePending = true
            val sent = dispatchGesture(gesture.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (!worker.isShutdown) worker.execute {
                        if (game === current) {
                            current.expected = expected
                            current.deadline = SystemClock.elapsedRealtime() + 5500
                            current.previous = null
                            current.touching = false
                        }
                        gesturePending = false
                    }
                }
                override fun onCancelled(gestureDescription: GestureDescription) {
                    if (game === current) stop("落子手势被中断")
                    gesturePending = false
                }
            }, null)
            if (!sent) {
                gesturePending = false
                stop("系统拒绝落子手势")
            }
        }
    }
}
