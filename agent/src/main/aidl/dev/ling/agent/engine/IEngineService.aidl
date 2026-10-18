// 引擎服务：由 Shizuku 以 shell 身份承载的 UserService。
//
// 为什么要由它来持有 socket：Android 的 SELinux 不允许 shell 进程连接应用创建的
// 抽象 socket（反之亦然）。所以必须在 shell 侧建 socket、接受引擎连接，再把
// 文件描述符通过 Binder 交给应用——跨域只发生在 Binder 上，这是允许的。
package dev.ling.agent.engine;

import android.os.ParcelFileDescriptor;

interface IEngineService {

    /**
     * 在 shell 侧创建监听 socket（名字为 `scrcpy_<scidHex>`）。
     * 必须在 startEngine 之前调用。
     */
    boolean prepareServer(String scidHex);

    /**
     * 以 shell 身份启动 scrcpy-server；引擎会反向连回上面的 socket。
     * 注意：scid 参数按**十六进制**解析（scrcpy Options.parse 用 radix 16）。
     *
     * @return 空字符串表示已启动；否则为错误信息。
     */
    String startEngine(String jarPath, String scidHex, int maxSize, int maxFps, int bitRate);

    /** 接受视频流连接并交出 fd。阻塞直到引擎连入。 */
    ParcelFileDescriptor acceptVideo();

    /** 接受控制流连接并交出 fd。必须在 acceptVideo 之后调用（协议规定的顺序）。 */
    ParcelFileDescriptor acceptControl();

    /** 停止引擎进程。返回是否确实停掉了。 */
    boolean stopEngine();

    /** 引擎是否在运行。 */
    boolean isRunning();

    /** 引擎最近输出（用于界面展示与排障）。 */
    String recentOutput();

    /** 本服务进程的身份，便于自检（应为 uid=2000 shell）。 */
    String identity();
}
