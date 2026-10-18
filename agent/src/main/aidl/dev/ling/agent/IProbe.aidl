// 探针接口：由 Shizuku 以 shell 身份承载的 UserService 实现。
// AIDL 是跨进程通信的标准做法，Shizuku 的 UserService 要求实现 IBinder。
package dev.ling.agent;

interface IProbe {
    /** 执行一条 shell 命令，返回 "exit=<code>\n<stdout+stderr>"。 */
    String run(String command);

    /** 返回本进程的身份信息（uid / SELinux 上下文），用于确认真的是 shell。 */
    String identity();
}
