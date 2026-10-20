package dev.ling.agent.session

/**
 * 长辈端界面的四种状态（对应 docs/design/ui-spec.md §3）。
 *
 * 状态必须能用"颜色 + 图标 + 大字"三件套同时表达，缺一不可——
 * 长辈不读小字，也不该被要求理解错误码。
 */
enum class AssistPhase {
    /** 未开启协助。 */
    Idle,

    /** 正在准备（释放引擎、启动服务、等待引擎连入）。 */
    Preparing,

    /** 已就绪，等待对方连接。 */
    Ready,

    /** 正在被协助。 */
    Active,

    /** 出错。error 字段里是给长辈看的人话。 */
    Error,
}

data class AgentUiState(
    val phase: AssistPhase = AssistPhase.Idle,
    val detail: String = "",
    val info: AgentSession.SessionInfo? = null,
    val error: String? = null,
    val controllerConnected: Boolean = false,
)
