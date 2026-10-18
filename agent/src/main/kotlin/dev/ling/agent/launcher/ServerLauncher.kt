package dev.ling.agent.launcher

import dev.ling.agent.engine.IEngineService

/**
 * "以 shell 身份把引擎跑起来"这件事的抽象。
 *
 * 之所以要这层接口：权限来源是最不稳定的一环（Shizuku 版本、ROM 差异、未来系统限制）。
 * 把变化关在这里，其余代码不需要知道引擎是怎么起来的。
 */
interface ServerLauncher {

    /** 绑定后端服务（Shizuku 的 UserService）。 */
    fun bind(onReady: (IEngineService) -> Unit, onError: (String) -> Unit)

    fun unbind()

    /** 权限是否已具备。 */
    fun isAuthorized(): Boolean
}
