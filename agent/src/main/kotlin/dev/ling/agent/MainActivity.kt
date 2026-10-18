package dev.ling.agent

import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

/**
 * T009 探针界面：验证 Shizuku 能否以 shell 身份执行 app_process 拉起 scrcpy-server。
 *
 * 这是整个项目最大的技术风险点，所以先用最直白的方式把它问清楚：
 * 1) Shizuku 是否在运行、是否已授权本应用；
 * 2) 绑定 UserService 后，那个进程真的是 shell 身份吗；
 * 3) 以该身份执行 app_process 加载 scrcpy-server 能不能跑起来。
 *
 * 探针成功后，这个界面会被替换为真正的长辈端界面。
 */
class MainActivity : Activity() {

    private lateinit var output: TextView
    private var probe: IProbe? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, ShizukuProbeService::class.java))
            .daemon(false)
            .processNameSuffix("probe")
            .debuggable(BuildConfig.DEBUG)
            .version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            probe = IProbe.Stub.asInterface(binder)
            log("UserService 已连接")
            runProbe()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            probe = null
            log("UserService 已断开")
        }
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            log("授权结果 requestCode=$requestCode：${if (grantResult == 0) "已允许" else "被拒绝（$grantResult）"}")
            if (grantResult == 0) connectUserService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        output = TextView(this).apply {
            textSize = 13f
            setPadding(24, 24, 24, 24)
            typeface = android.graphics.Typeface.MONOSPACE
        }

        val runButton = Button(this).apply {
            text = "运行探针"
            setOnClickListener { startProbe() }
        }

        setContentView(
            ScrollView(this).apply {
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(runButton)
                        addView(output)
                    }
                )
            }
        )

        Shizuku.addBinderReceivedListenerSticky { log("Shizuku 服务已就绪") }
        Shizuku.addBinderDeadListener { log("Shizuku 服务已断开") }
        Shizuku.addRequestPermissionResultListener(permissionListener)

        log("=== 长辈端探针（T009）===")
        log("Shizuku 绑定状态：${Shizuku.pingBinder()}")

        // 开发期用 adb 触发，避免依赖触摸自动化：
        //   adb shell am start -n dev.ling.agent/.MainActivity -e run_probe true
        if (intent?.getBooleanExtra(EXTRA_RUN_PROBE, false) == true) {
            log("（由 intent 触发，1 秒后自动运行探针）")
            output.postDelayed({ startProbe() }, 1000)
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    private fun startProbe() {
        if (!Shizuku.pingBinder()) {
            log("Shizuku 未运行：请先打开 Shizuku 应用并启动服务")
            return
        }
        val granted = Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        log("本应用是否已获 Shizuku 授权：$granted")
        if (granted) connectUserService() else Shizuku.requestPermission(REQUEST_CODE)
    }

    private fun connectUserService() {
        log("正在绑定 UserService…")
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (t: Throwable) {
            Log.e(TAG, "绑定失败", t)
            log("绑定异常：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 探针正文：每一步都打印原始输出，不做美化，便于判断真假。 */
    private fun runProbe() {
        val p = probe ?: return
        try {
            log("\n── 1. UserService 进程身份 ──")
            log(p.identity())

            log("\n── 2. 引擎文件是否可见 ──")
            log(p.run("ls -l $SCRCPY_JAR"))

            log("\n── 3. 以 shell 身份执行 app_process 启动 scrcpy-server ──")
            log(p.run("timeout 3 env CLASSPATH=$SCRCPY_JAR app_process / " +
                    "com.genymobile.scrcpy.Server $SCRCPY_VERSION " +
                    "scid=1 log_level=info audio=false control=true " +
                    "tunnel_forward=true max_size=1024"))
            log("（exit=124 表示进程一直在运行、被 timeout 正常掐断，这正是期望结果）")
        } catch (t: Throwable) {
            Log.e(TAG, "探针失败", t)
            log("探针异常：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        runOnUiThread { output.append("$line\n") }
    }

    private companion object {
        const val TAG = "AgentProbe"
        const val REQUEST_CODE = 1001
        const val EXTRA_RUN_PROBE = "run_probe"
        const val SCRCPY_JAR = "/data/local/tmp/scrcpy-server.jar"
        const val SCRCPY_VERSION = "5.0.1"
    }
}
