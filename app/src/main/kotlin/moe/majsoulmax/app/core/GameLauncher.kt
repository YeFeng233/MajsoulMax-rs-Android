package moe.majsoulmax.app.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import moe.majsoulmax.app.R

object GameLauncher {
    const val PACKAGE_NAME = "com.soulgamechst.majsoul"
    const val DOWNLOAD_URL = "https://www.maj-soul.com/#/home"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun open(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)
        val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL))
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(context, R.string.game_launch_failed, Toast.LENGTH_LONG).show() }
    }
}
