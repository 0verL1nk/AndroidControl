package dev.ling.agent.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.ArrayDeque
import java.util.Locale

/**
 * 长辈端的语音播报（"对方已连接"这类状态变化）。
 *
 * 长辈不一定盯着屏幕，但一定听得到声音。只在**状态真正发生变化**时播报，
 * 不做实时朗读——那会变成噪音。
 *
 * 如果设备没装中文语音包，就安静地什么都不做，绝不用英文腔调念中文。
 */
class Announcer(context: Context) {

    private var engine: TextToSpeech? = null
    private var usable = false
    private val pending = ArrayDeque<String>()

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            val tts = engine ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "语音播报不可用（初始化失败）")
                return@TextToSpeech
            }
            val language = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
            usable = language != TextToSpeech.LANG_MISSING_DATA &&
                language != TextToSpeech.LANG_NOT_SUPPORTED
            if (!usable) {
                Log.w(TAG, "缺少中文语音包，关闭播报")
                return@TextToSpeech
            }
            synchronized(pending) {
                while (pending.isNotEmpty()) speakNow(pending.removeFirst())
            }
        }
    }

    fun announce(text: String) {
        if (usable) {
            speakNow(text)
            return
        }
        // 引擎还没初始化完：最多攒三条，避免一口气念一串过时状态
        synchronized(pending) {
            if (pending.size < 3) pending.addLast(text)
        }
    }

    private fun speakNow(text: String) {
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "agent-state")
    }

    fun release() {
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
        synchronized(pending) { pending.clear() }
    }

    private companion object {
        const val TAG = "Announcer"
    }
}
