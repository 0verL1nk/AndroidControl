package dev.ling.agent

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * 长辈端。当前仅用于验证工具链，界面在 T024 落地。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "长辈端（开发中）"
            textSize = 24f
        })
    }
}
