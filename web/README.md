# 网页控制端

浏览器里查看并操作另一台手机。与 Android 控制端共用同一套协议：

- 两条 WebSocket（`video <哈希>` / `control <哈希>`）连到中继的 `/ws`；
- 浏览器用 **WebCodecs** 解码 H.264，触摸/按键直接发送 scrcpy 控制消息；
- 文字输入走 `INJECT_TEXT`；语音走 `audio` 角色（16 kHz PCM，MediaRecorder 之外的
  AudioWorklet 采集/播放，带回声消除）；
- 只在**安全上下文**（HTTPS 或 `localhost`）下可用，因此线上必须给中继一个 `wss://` 入口。

## 本地开发

```bash
npm install
NEXT_PUBLIC_RELAY_URL=ws://localhost:27180 npm run dev
```

打开 <http://localhost:3000>，输入对方手机上的 6 位数字即可。

## 测试

```bash
npm test                              # 流解析单测（node:test + 类型剥离，无额外依赖）
node ../tools/e2e-web.mjs             # 真实中继 + 脚本化长辈端 + WebSocket 控制器
```

`src/lib/parse-stream.ts` 是纯函数分帧器（设备头 / 会话包 / 配置包 / 帧），
`src/lib/scrcpy.ts` 是控制消息与 Annex-B 的字节布局；这两处改动必须保持与
`protocol/` 逐字节一致。

## 部署到 Vercel

1. 导入仓库，Root Directory 选 `web/`；
2. 环境变量 `NEXT_PUBLIC_RELAY_URL=wss://<你的中继域名>`；
3. 中继的 TLS/WSS 入口见 `docs/ops/relay-node.md`（Cloudflare Tunnel 或 Caddy 反代）。

`NEXT_PUBLIC_RELAY_URL` 会在构建时写进前端；也可以在连接页的「高级设置」里临时修改，
修改后的地址保存在浏览器本地。
