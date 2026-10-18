package dev.ling.agent

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.ling.agent.launcher.ShizukuLauncher
import dev.ling.agent.session.AgentSession
import rikka.shizuku.Shizuku

/**
 * 长辈端主界面。
 *
 * 现在这个阶段的目标不是好看，而是**让每一步都可观测**：Shizuku 是否就绪、引擎是否起来、
 * 端口和临时口令是多少。适老化界面（大字号、一键求助）在 M4 重做，届时把这里的诊断信息
 * 收进"高级"折叠区。
 */
class MainActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var toggle: Button
    private var session: AgentSession? = null

    private val ui = Handler(Looper.getMainLooper())

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            log("Shizuku 授权：${if (grantResult == 0) "已允许" else "被拒绝（$grantResult）"}")
            if (grantResult == 0) startSession()
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
            setOnClickListener { if (session == null) requestAndStart() else stopSession() }
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

        log("长辈端已启动")
        log("Shizuku 运行中：${Shizuku.pingBinder()}")
        log("Shizuku 版本：${runCatching { Shizuku.getVersion() }.getOrDefault(-1)}")
        if (Shizuku.pingBinder()) {
            log("本应用已获授权：${Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED}")
        }

        // 打开即允许协助：长辈点开这个应用本身就是明确的操作，
        // 因此不做二次确认，直接进入"可被连接"状态（控制端连上后会有可见提示）。
        ui.postDelayed({ requestAndStart() }, 600)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        session?.stop()
        super.onDestroy()
    }

    private fun requestAndStart() {
        if (!Shizuku.pingBinder()) {
            log("Shizuku 未运行：请先启动 Shizuku 服务")
            return
        }
        if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startSession()
        } else {
            log("请求 Shizuku 授权…")
            Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    private fun startSession() {
        if (session != null) return
        val s = AgentSession(
            context = this,
            launcher = ShizukuLauncher(this),
            onState = { log(it) },
            onReady = { info ->
                log("")
                log("════ 控制端连接信息 ════")
                log("地址：${localIpAddress()}")
                log("控制端口：${info.controlPort}")
                log("视频端口：${info.videoPort}")
                log("口令：${info.token}")
                log("═══════════════════")
                ui.post { toggle.text = "结束协助" }
            },
        )
        session = s
        s.start()
    }

    private fun stopSession() {
        session?.stop()
        session = null
        toggle.text = "允许协助"
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
        const val EXTRA_AUTO_START = "auto_start"
    }
}
