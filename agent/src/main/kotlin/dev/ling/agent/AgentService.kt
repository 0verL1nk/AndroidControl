package dev.ling.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import dev.ling.agent.launcher.ShizukuLauncher
import dev.ling.agent.session.AgentSession
import dev.ling.agent.session.AgentUiState
import dev.ling.agent.session.AssistPhase
import dev.ling.agent.trust.PairingStore
import dev.ling.agent.ui.Announcer
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
    private var announcer: Announcer? = null
    private var lastPhase: AssistPhase? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startAsForeground(withMicrophone = true)
        indicator = ControlIndicator(this)
        announcer = Announcer(this)
    }

    /**
     * 以"屏幕提示 + 麦克风"两种类型进入前台。
     *
     * 语音需要 `microphone` 类型：否则应用退到后台后，"仅在使用中允许"的录音权限
     * 会被系统收回，孩子那边就只能单向说话。
     * 但从开机自启（后台）启动时，系统不允许带上麦克风类型，
     * 所以失败就退回 `specialUse`——协助本身不受影响，只是语音要等应用可见后重试。
     */
    private fun startAsForeground(withMicrophone: Boolean) {
        val notification = buildNotification("正在准备协助…")
        val specialUse = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        val types = if (withMicrophone) {
            specialUse or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            specialUse
        }
        runCatching { startForeground(NOTIFICATION_ID, notification, types) }
            .onFailure { error ->
                Log.w(TAG, "前台服务类型 $types 启动失败，退回 specialUse：${error.message}")
                runCatching { startForeground(NOTIFICATION_ID, notification, specialUse) }
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ROTATE -> {
                // 换码即作废旧配对：重启会话会让中继按新哈希重新登记，
                // 正在进行的会话也会断开（这正是"怀疑泄露"时应有的效果）。
                val fresh = PairingStore(this).resetToken()
                Log.i(TAG, "配对码已轮换：$fresh")
                stopSession()
                startSession()
                return START_STICKY
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
        announcer?.release()
        announcer = null
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
                // 状态变化时播报一句：长辈不一定看着屏幕，但听得到
                when {
                    phase == AssistPhase.Active && lastPhase != AssistPhase.Active ->
                        announcer?.announce("家人已连接，正在看你的手机")
                    phase == AssistPhase.Ready && lastPhase == AssistPhase.Active ->
                        announcer?.announce("家人已断开")
                }
                lastPhase = phase
            },
            onSensitivePage = { packageName ->
                // 回调来自轮询线程，窗口与通知都要回主线程处理
                mainHandler.post {
                    if (packageName == null) {
                        if (_state.value.phase == AssistPhase.Active) {
                            indicator?.show(ControlIndicator.Mode.ASSISTED)
                        }
                    } else {
                        val label = appLabel(packageName)
                        indicator?.show(ControlIndicator.Mode.SENSITIVE, label)
                        notify(buildNotification("敏感页面：$label"))
                    }
                }
            },
        )
        session = created
        created.start()
    }

    /** 包名 → 应用名，给长辈看人话而不是包名。 */
    private fun appLabel(packageName: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

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
        const val ACTION_ROTATE = "dev.ling.agent.action.ROTATE"

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

        /** 换一个新配对码，并让中继按新哈希重新登记。 */
        fun rotateToken(context: Context) {
            context.startForegroundService(
                Intent(context, AgentService::class.java).setAction(ACTION_ROTATE),
            )
        }
    }
}
