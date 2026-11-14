package local.chessbridge

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            status.text = accessibilityStatus()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        fun label(text: String) = TextView(this).apply { this.text = text; textSize = 16f; body.addView(this) }
        fun help(title: String, description: String) {
            label(title).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                val gap = (10 * resources.displayMetrics.density).toInt()
                setPadding(0, gap, 0, 0)
            }
            label(description).textSize = 14f
        }
        fun button(text: String, action: () -> Unit) {
            body.addView(Button(this).apply { this.text = text; setOnClickListener { action() } })
        }
        label("象棋助手")
        status = label(accessibilityStatus())
        button("打开无障碍设置") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        label("进入系统设置，开启象棋助手的截图和点击权限。").textSize = 14f
        button("启动助手") {
            val service = ChessService.instance
            if (service == null) Toast.makeText(this, accessibilityStatus(), Toast.LENGTH_LONG).show()
            else {
                service.startAssistant()
                moveTaskToBack(true)
            }
        }
        label("打开全局悬浮窗，然后返回棋局操作。").textSize = 14f
        button("关闭悬浮窗") { ChessService.instance?.closeAssistant() }
        label("停止自动走棋，关闭悬浮窗并清除定位。").textSize = 14f

        help("悬浮窗怎么用", "长按左侧状态文字，再拖到不挡棋盘的位置。")
        help("定位", "一般不需要点击，只有提示无法定位时才需要。")
        help("执红", "红方轮到你时点两次，确认后自动读盘并接管红方。")
        help("执黑", "黑方轮到你时点两次，确认后自动读盘并接管黑方。")
        help("停止", "结束本次自动走棋，保留悬浮窗和手动定位。")
        setContentView(ScrollView(this).apply { addView(body) })
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun accessibilityStatus(): String {
        if (ChessService.instance != null) return ChessService.status
        val component = ComponentName(this, ChessService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
        return if (enabled) "无障碍已开启，但服务未连接。请在无障碍设置中关闭再开启本助手。"
            else "请开启「象棋助手」无障碍服务"
    }
}
