package app.rilan.daily

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.rilan.daily.ui.RilanRoot
import app.rilan.daily.ui.theme.RilanTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        // MCP 不在 onCreate 里同步启动：RilanRoot 在 ON_RESUME 会调用同一入口，
        // 那时应用已在前台，前台服务不会被 Android 14/15 的后台启动规则拒绝。
        setContent {
            RilanTheme {
                RilanRoot(viewModel)
            }
        }
    }
}
