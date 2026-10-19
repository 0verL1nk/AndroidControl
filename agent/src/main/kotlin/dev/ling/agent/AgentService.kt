package dev.ling.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dev.ling.agent.launcher.ShizukuLauncher
import dev.ling.agent.session.AgentSession
import dev.ling.agent.session.AgentUiState
import dev.ling.agent.session.AssistPhase
import dev.ling.agent.ui.ControlIndicator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 长辈端的常驻服务。
 *
 * 为什么必须是前台服务：被协助时长辈端 App 通常不在前台（甚至屏幕是黑的），
 * 普通进程会被系统冻结/回收，会话随即中断。前台服务 + 常驻通知是唯一
 * 不需要 root 的自保方式，也顺带让"正在被协助"对长辈始终可见。
 *
 * 服务只做三件事：拉起会话、维持会话、在通知里给出可见状态。
 */
class AgentService : Service() {

    private var session: AgentSession? = null
    private var indicator: ControlIndicator? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在准备协助…"))
        indicator = ControlIndicator(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startSession()
        // 被杀后自动重建：长辈端"零操作"的前提
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopSession()
        mainHandler.post {
            indicator?.hide()
            indicator = null
        }
        super.onDestroy()
    }

    private fun startSession() {
        if (session != null) return

        val created = AgentSession(
            context = this,
            launcher = ShizukuLauncher(this),
            onState = { message ->
                Log.i(TAG, message)
                STATE = message
                _state.value = _state.value.copy(detail = message)
                notify(buildNotification(message))
            },
            onReady = { info ->
                INFO = info
                _state.value = _state.value.copy(info = info)
                notify(buildNotification("已准备好，等待家人连接"))
            },
            onPhase = { phase, message ->
                Log.i(TAG, "阶段：$phase - $message")
                _state.value = _state.value.copy(
                    phase = phase,
                    detail = message,
                    error = if (phase == AssistPhase.Error) message else null,
                    controllerConnected = phase == AssistPhase.Active,
                    info = INFO,
                )
                notify(buildNotification(if (phase == AssistPhase.Active) "正在被协助" else message))
                // 被协助时给屏幕加一圈荧光：这是长辈"看得见"的边界，结束即消失。
                // 注意：添加窗口必须在主线程（回调发生在网络线程，直接加会抛
                // "Can't create handler ... Looper.prepare()"）。
                mainHandler.post {
                    if (phase == AssistPhase.Active) indicator?.show() else indicator?.hide()
                }
            },
        )
        session = created
        created.start()
    }

    private fun stopSession() {
        indicator?.hide()
        session?.stop()
        session = null
        STATE = "已结束协助"
        _state.value = AgentUiState(phase = AssistPhase.Idle, detail = "已结束协助")
    }

    /**
     * 通知更新走主线程：它是一次跨进程调用，直接在网络线程里调用有可能把线程卡住
     * （实测中继线程就这样无声地停住过）。网络线程只负责投递。
     */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun notify(notification: Notification) {
        mainHandler.post {
            runCatching {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "协助状态",
            NotificationManager.IMPORTANCE_LOW, // 不发声，但常驻可见
        ).apply {
            description = "显示当前是否正在被家人协助"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("远程协助")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "结束协助", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "agent_status"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "dev.ling.agent.action.START"
        const val ACTION_STOP = "dev.ling.agent.action.STOP"

        /** 供界面读取的当前状态（服务跨进程不可见，这里用进程内共享）。 */
        @Volatile
        var STATE: String = "未启动"
            private set

        @Volatile
        var INFO: AgentSession.SessionInfo? = null
            private set

        private val _state = MutableStateFlow(AgentUiState())

        /** 界面直接订阅这个状态流；服务是唯一事实来源。 */
        val state: StateFlow<AgentUiState> = _state.asStateFlow()

        /** 让界面能主动进入出错态（例如 Shizuku 未运行）。 */
        fun reportError(message: String) {
            _state.value = _state.value.copy(
                phase = AssistPhase.Error,
                detail = message,
                error = message,
            )
        }

        fun start(context: Context) {
            val intent = Intent(context, AgentService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AgentService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
