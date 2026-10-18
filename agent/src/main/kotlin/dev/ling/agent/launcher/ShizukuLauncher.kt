package dev.ling.agent.launcher

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import dev.ling.agent.BuildConfig
import dev.ling.agent.engine.EngineService
import dev.ling.agent.engine.IEngineService
import rikka.shizuku.Shizuku

/**
 * 通过 Shizuku 的 UserService 拿到 shell 身份。
 *
 * 注意：Shizuku 的公开 API 中 `newProcess` 已被移除，UserService 是唯一正路；
 * 而 UserService 在**联发科机型 + Shizuku 13.6.0** 上会崩溃，故版本固定为 13.5.4
 * （见 docs/DECISIONS.md ADR-014）。
 */
class ShizukuLauncher(private val context: Context) : ServerLauncher {

    private var service: IEngineService? = null

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(context, EngineService::class.java))
            .daemon(false)
            .processNameSuffix("engine")
            .debuggable(BuildConfig.DEBUG)
            .version(2)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IEngineService.Stub.asInterface(binder)
            Log.i(TAG, "引擎服务已连接")
            pendingReady?.invoke(service!!)
            pendingReady = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "引擎服务已断开")
            service = null
        }
    }

    private var pendingReady: ((IEngineService) -> Unit)? = null

    override fun bind(onReady: (IEngineService) -> Unit, onError: (String) -> Unit) {
        if (!Shizuku.pingBinder()) {
            onError("Shizuku 未运行")
            return
        }
        service?.let { onReady(it); return }

        pendingReady = onReady
        try {
            Shizuku.bindUserService(args, connection)
        } catch (t: Throwable) {
            pendingReady = null
            Log.e(TAG, "绑定引擎服务失败", t)
            onError("绑定引擎服务失败：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun unbind() {
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        service = null
    }

    override fun isAuthorized(): Boolean =
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "ShizukuLauncher"
    }
}
