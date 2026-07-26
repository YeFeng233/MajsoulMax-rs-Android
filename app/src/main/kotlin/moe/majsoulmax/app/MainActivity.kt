package moe.majsoulmax.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import moe.majsoulmax.app.ui.AppRoot
import moe.majsoulmax.app.ui.theme.MajsoulMaxTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MajsoulMaxTheme {
                AppRoot()
            }
        }
    }
}
