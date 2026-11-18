package com.minids

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalView
import com.minids.ui.ChatScreen
import com.minids.ui.MiniTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: ChatViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            MiniTheme(dark = state.settings.dark) {
                val view = LocalView.current
                SideEffect {
                    val window = (view.context as? Activity)?.window ?: return@SideEffect
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !state.settings.dark
                        isAppearanceLightNavigationBars = !state.settings.dark
                    }
                }
                ChatScreen(vm)
            }
        }
    }
}
