"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { PointerEvent as ReactPointerEvent } from "react";
import { ACTION_DOWN, ACTION_MOVE, ACTION_UP, KEYCODES, type KeyName } from "@/lib/scrcpy";
import type { RemoteSession, SessionState } from "@/lib/remote-session";

const RELAY_STORAGE_KEY = "ac.relayUrl";

type ControlAction = { key: KeyName; label: string };

const CONTROLS: ControlAction[] = [
  { key: "back", label: "返回" },
  { key: "home", label: "主页" },
  { key: "recents", label: "最近" },
  { key: "volumeDown", label: "音量−" },
  { key: "volumeUp", label: "音量+" },
  { key: "wake", label: "亮屏" },
];

export function RemoteAssist() {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const sessionRef = useRef<RemoteSession | null>(null);

  const [token, setToken] = useState("");
  const [relayUrl, setRelayUrl] = useState(() => process.env.NEXT_PUBLIC_RELAY_URL ?? "");
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [state, setState] = useState<SessionState>("idle");
  const [detail, setDetail] = useState<string | undefined>(undefined);
  const [fps, setFps] = useState(0);
  const [formError, setFormError] = useState<string | undefined>(undefined);

  useEffect(() => {
    const saved = window.localStorage.getItem(RELAY_STORAGE_KEY);
    if (saved) setRelayUrl(saved);
  }, []);

  const stopSession = useCallback(() => {
    sessionRef.current?.stop();
    sessionRef.current = null;
    setState("idle");
    setFps(0);
  }, []);

  useEffect(() => stopSession, [stopSession]);

  const connect = useCallback(async () => {
    const trimmedToken = token.trim();
    const relay = normalizeRelayUrl(relayUrl);
    if (!/^\d{6}$/.test(trimmedToken)) {
      setFormError("请输入对方手机上的 6 位数字");
      return;
    }
    if (!relay) {
      setFormError("请填写中继地址（wss://…）");
      setShowAdvanced(true);
      return;
    }
    if (!window.isSecureContext) {
      setFormError("浏览器控制端需要 HTTPS 或 localhost");
      return;
    }
    if (!("VideoDecoder" in window)) {
      setFormError("当前浏览器不支持 WebCodecs，请使用 Chrome 或 Edge");
      return;
    }
    if (window.location.protocol === "https:" && relay.startsWith("ws://")) {
      setFormError("HTTPS 页面不能连接 ws://，请使用 wss://");
      return;
    }
    setFormError(undefined);
    window.localStorage.setItem(RELAY_STORAGE_KEY, relay);

    const canvas = canvasRef.current;
    if (!canvas) return;

    sessionRef.current?.stop();
    // 视频解码只在真正连接时才加载，首屏保持很小
    const { RemoteSession } = await import("@/lib/remote-session");
    const session = new RemoteSession({
      relayUrl: relay,
      canvas,
      onState: (next, message) => {
        setState(next);
        setDetail(message);
      },
      onFps: setFps,
    });
    sessionRef.current = session;
    await session.connect(trimmedToken);
  }, [relayUrl, token]);

  const sendTouch = useCallback(
    (event: React.PointerEvent<HTMLCanvasElement>, action: number) => {
      const session = sessionRef.current;
      const canvas = canvasRef.current;
      if (!session || !canvas || !canvas.width || !canvas.height) return;
      const rect = canvas.getBoundingClientRect();
      // object-contain：先算出画面在元素内的实际位置
      const scale = Math.min(rect.width / canvas.width, rect.height / canvas.height);
      const width = canvas.width * scale;
      const height = canvas.height * scale;
      const left = rect.left + (rect.width - width) / 2;
      const top = rect.top + (rect.height - height) / 2;
      const x = Math.floor((event.clientX - left) / scale);
      const y = Math.floor((event.clientY - top) / scale);
      if (x < 0 || y < 0 || x >= canvas.width || y >= canvas.height) return;
      session.sendTouch(action, x, y);
    },
    [],
  );

  const lastMoveRef = useRef(0);
  const activePointerRef = useRef<number | null>(null);

  return (
    <div className="flex h-dvh flex-col bg-neutral-950 text-neutral-100">
      <main className="relative flex-1 overflow-hidden bg-black">
        <canvas
          ref={canvasRef}
          className="absolute inset-0 h-full w-full touch-none object-contain"
          onPointerDown={(event) => {
            if (state !== "live") return;
            event.preventDefault();
            activePointerRef.current = event.pointerId;
            try {
              event.currentTarget.setPointerCapture(event.pointerId);
            } catch {
              // 合成事件或指针已经失效时浏览器会抛错，不影响后续输入
            }
            sendTouch(event, ACTION_DOWN);
          }}
          onPointerMove={(event) => {
            if (state !== "live" || activePointerRef.current !== event.pointerId) return;
            const now = performance.now();
            if (now - lastMoveRef.current < 16) return;
            lastMoveRef.current = now;
            sendTouch(event, ACTION_MOVE);
          }}
          onPointerUp={(event) => {
            if (activePointerRef.current !== event.pointerId) return;
            activePointerRef.current = null;
            sendTouch(event, ACTION_UP);
          }}
          onPointerCancel={(event) => {
            if (activePointerRef.current !== event.pointerId) return;
            activePointerRef.current = null;
            sendTouch(event, ACTION_UP);
          }}
        />

        {state === "live" && (
          <div className="pointer-events-none absolute inset-x-0 top-0 flex items-center gap-2 bg-gradient-to-b from-black/70 to-transparent px-4 py-3 text-xs">
            <span className="flex items-center gap-1.5 rounded-full bg-emerald-500/15 px-3 py-1 text-emerald-300">
              <span className="size-1.5 rounded-full bg-emerald-400" />
              正在控制
            </span>
            <span className="rounded-full bg-black/50 px-3 py-1 text-neutral-300">
              {fps} fps
            </span>
            <span className="flex-1" />
            <button
              onClick={stopSession}
              className="pointer-events-auto rounded-full bg-white/10 px-3 py-1 text-neutral-200 transition hover:bg-white/20"
            >
              断开
            </button>
          </div>
        )}

        {state !== "live" && (
          <div className="absolute inset-0 grid place-items-center bg-[radial-gradient(circle_at_50%_20%,#1b2230_0%,#0b0d10_60%)] p-6">
            {state === "idle" ? (
              <ConnectCard
                token={token}
                onToken={(value) => {
                  setToken(value.replace(/\D/g, "").slice(0, 6));
                  setFormError(undefined);
                }}
                relayUrl={relayUrl}
                onRelayUrl={(value) => {
                  setRelayUrl(value);
                  setFormError(undefined);
                }}
                showAdvanced={showAdvanced}
                onToggleAdvanced={() => setShowAdvanced((open) => !open)}
                error={formError}
                onConnect={connect}
              />
            ) : (
              <ConnectingCard state={state} detail={detail} onCancel={stopSession} onRetry={connect} />
            )}
          </div>
        )}
      </main>

      {state === "live" && (
        <footer className="flex justify-evenly gap-2 border-t border-white/5 bg-neutral-900/95 px-3 pb-[calc(0.75rem+env(safe-area-inset-bottom))] pt-3">
          {CONTROLS.map((control) => (
            <button
              key={control.key}
              onClick={() => {
                const keycode = KEYCODES[control.key];
                sessionRef.current?.sendKey(keycode);
              }}
              className="min-w-16 rounded-xl bg-neutral-800 px-3 py-2.5 text-sm text-neutral-200 transition active:bg-neutral-700"
            >
              {control.label}
            </button>
          ))}
        </footer>
      )}
    </div>
  );
}

function ConnectCard(props: {
  token: string;
  onToken: (value: string) => void;
  relayUrl: string;
  onRelayUrl: (value: string) => void;
  showAdvanced: boolean;
  onToggleAdvanced: () => void;
  error?: string;
  onConnect: () => void;
}) {
  return (
    <div className="w-full max-w-md rounded-3xl border border-white/10 bg-neutral-900/80 p-7 shadow-2xl backdrop-blur">
      <div className="mb-6 flex items-center gap-3">
        <div className="grid size-11 place-items-center rounded-2xl bg-blue-500 text-white">
          <svg viewBox="0 0 24 24" className="size-6" fill="none" stroke="currentColor" strokeWidth={2}>
            <rect x="7" y="2.5" width="10" height="19" rx="2.5" />
            <path d="M11 18.5h2" strokeLinecap="round" />
          </svg>
        </div>
        <div>
          <h1 className="text-lg font-semibold tracking-tight">远程协助</h1>
          <p className="text-xs text-neutral-400">在浏览器里操作另一台手机</p>
        </div>
      </div>

      <label className="mb-2 block text-sm text-neutral-400">对方手机上的 6 位数字</label>
      <input
        value={props.token}
        onChange={(event) => props.onToken(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Enter") props.onConnect();
        }}
        inputMode="numeric"
        autoComplete="one-time-code"
        maxLength={6}
        placeholder="······"
        className="w-full rounded-2xl border border-white/10 bg-neutral-950 px-4 py-3.5 text-center text-3xl font-semibold tracking-[0.5em] text-white outline-none transition focus:border-blue-500"
      />

      <button
        onClick={props.onConnect}
        className="mt-4 w-full rounded-2xl bg-blue-600 py-3.5 text-base font-medium text-white transition hover:bg-blue-500 active:bg-blue-700"
      >
        连接
      </button>

      <button
        onClick={props.onToggleAdvanced}
        className="mt-3 w-full text-center text-xs text-neutral-500 transition hover:text-neutral-300"
      >
        {props.showAdvanced ? "收起高级设置" : "高级设置（中继地址）"}
      </button>

      {props.showAdvanced && (
        <div className="mt-3">
          <label className="mb-1.5 block text-xs text-neutral-500">中继地址</label>
          <input
            value={props.relayUrl}
            onChange={(event) => props.onRelayUrl(event.target.value)}
            placeholder="wss://relay.example.com"
            className="w-full rounded-xl border border-white/10 bg-neutral-950 px-3 py-2.5 text-sm text-neutral-200 outline-none focus:border-blue-500"
          />
          <p className="mt-1.5 text-[11px] leading-relaxed text-neutral-500">
            填你自己的中继节点。浏览器要求 HTTPS 页面使用 wss://。
          </p>
        </div>
      )}

      {props.error && <p className="mt-4 text-sm text-red-400">{props.error}</p>}

      <p className="mt-6 text-center text-[11px] leading-relaxed text-neutral-600">
        使用前请取得对方同意。画面与输入只经过你自己的中继节点。
      </p>
    </div>
  );
}

function ConnectingCard(props: {
  state: SessionState;
  detail?: string;
  onCancel: () => void;
  onRetry: () => void;
}) {
  const failed = props.state === "error";
  return (
    <div className="w-full max-w-sm rounded-3xl border border-white/10 bg-neutral-900/80 p-7 text-center shadow-2xl backdrop-blur">
      {failed ? (
        <>
          <div className="mx-auto mb-4 grid size-12 place-items-center rounded-full bg-red-500/15 text-red-400">
            <svg viewBox="0 0 24 24" className="size-6" fill="none" stroke="currentColor" strokeWidth={2}>
              <path d="M12 8v5M12 16.5h.01" strokeLinecap="round" />
              <circle cx="12" cy="12" r="9" />
            </svg>
          </div>
          <h2 className="text-base font-medium">连接没有成功</h2>
          <p className="mt-2 text-sm leading-relaxed text-neutral-400">
            {props.detail ?? "请稍后再试"}
          </p>
          <div className="mt-5 flex gap-3">
            <button
              onClick={props.onCancel}
              className="flex-1 rounded-xl bg-neutral-800 py-2.5 text-sm text-neutral-300"
            >
              返回
            </button>
            <button
              onClick={props.onRetry}
              className="flex-1 rounded-xl bg-blue-600 py-2.5 text-sm text-white"
            >
              重试
            </button>
          </div>
        </>
      ) : (
        <>
          <div className="mx-auto mb-4 size-10 animate-spin rounded-full border-2 border-white/15 border-t-blue-500" />
          <h2 className="text-base font-medium">正在连接…</h2>
          <p className="mt-2 text-sm text-neutral-400">{props.detail ?? "请稍候"}</p>
          <button
            onClick={props.onCancel}
            className="mt-5 w-full rounded-xl bg-neutral-800 py-2.5 text-sm text-neutral-300"
          >
            取消
          </button>
        </>
      )}
    </div>
  );
}

function normalizeRelayUrl(value: string): string | null {
  const trimmed = value.trim();
  if (!trimmed) return null;
  if (trimmed.startsWith("https://")) return "wss://" + trimmed.slice("https://".length);
  if (trimmed.startsWith("http://")) return "ws://" + trimmed.slice("http://".length);
  if (trimmed.startsWith("wss://") || trimmed.startsWith("ws://")) return trimmed.replace(/\/+$/, "");
  return "wss://" + trimmed.replace(/\/+$/, "");
}
