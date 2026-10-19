package dev.ling.agent

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

/**
 * 长辈端主界面。
 *
 * 当前阶段以"可观测"为主：每一步的状态都能看到。适老化重做在 M4——
 * 届时主界面只保留状态大字与一个按钮，端口/口令/日志收进隐藏面板。
 *
 * 真正的会话由 [AgentService] 承载，Activity 退出不影响协助（前台服务保活）。
 */
class MainActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var toggle: Button
    private val ui = Handler(Looper.getMainLooper())
    private var lastState = ""

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            log("Shizuku 授权：${if (grantResult == 0) "已允许" else "被拒绝（$grantResult）"}")
            if (grantResult == 0) startAssist()
        }

    private val statePoller = object : Runnable {
        override fun run() {
            val state = AgentService.STATE
            if (state != lastState) {
                lastState = state
                log(state)
                if (state.contains("已准备好")) {
                    AgentService.INFO?.let { info ->
                        val ip = localIpAddress()
                        log("")
                        log("════ 控制端连接信息 ════")
                        log("地址：$ip")
                        log("控制端口：${info.controlPort}")
                        log("视频端口：${info.videoPort}")
                        log("口令：${info.token}")
                        log("═══════════════════")
                    }
                }
            }
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        output = TextView(this).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 32, 32, 32)
        }
        toggle = Button(this).apply {
            text = "允许协助"
            setOnClickListener {
                if (AgentService.STATE.contains("已准备好") || AgentService.STATE.contains("准备")) {
                    AgentService.stop(this@MainActivity)
                    toggle.text = "允许协助"
                } else {
                    requestAndStart()
                }
            }
        }

        setContentView(
            ScrollView(this).apply {
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.FILL_HORIZONTAL
                        addView(toggle)
                        addView(output)
                    }
                )
            }
        )

        Shizuku.addRequestPermissionResultListener(permissionListener)
        requestNotificationPermission()
        log("长辈端已启动")
        log("Shizuku 运行中：${Shizuku.pingBinder()}")
        if (Shizuku.pingBinder()) {
            log("本应用已获授权：${Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED}")
        }

        ui.post(statePoller)
        // 打开即进入"可被协助"：长辈点开这个应用本身就是明确的操作
        ui.postDelayed({ requestAndStart() }, 600)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        ui.removeCallbacks(statePoller)
        super.onDestroy()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
    }

    private fun requestAndStart() {
        if (!Shizuku.pingBinder()) {
            log("Shizuku 未运行：请先启动 Shizuku 服务")
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            startAssist()
        } else {
            log("请求 Shizuku 授权…")
            Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    private fun startAssist() {
        log("启动协助服务…")
        AgentService.start(this)
        toggle.text = "结束协助"
    }

    private fun localIpAddress(): String =
        runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull() ?: "（无网络）"

    private fun log(line: String) {
        android.util.Log.i(TAG, line)
        ui.post { output.append("$line\n") }
    }

    private companion object {
        const val TAG = "AgentMain"
        const val REQUEST_CODE = 1001
    }
}
