package com.lumen.editor

import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.lumen.editor.ui.EditorScreen
import com.lumen.editor.ui.theme.LumenTheme

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) intentImage(intent)?.let(vm::open)
        setContent { LumenTheme { EditorScreen(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intentImage(intent)?.let(vm::open)
    }

    private fun intentImage(i: Intent?): Uri? = when (i?.action) {
        Intent.ACTION_SEND ->
            if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
        Intent.ACTION_EDIT, Intent.ACTION_VIEW -> i.data
        else -> null
    }

    @Deprecated("Deprecated in Java")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) vm.onTrimMemory()
    }
}
