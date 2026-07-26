package moe.majsoulmax.app

import android.app.Application
import moe.majsoulmax.app.core.AssetInstaller
import moe.majsoulmax.app.core.Paths
import moe.majsoulmax.app.service.NotificationHelper

/**
 * Runs in both the UI process and `:core`, so keep this cheap and idempotent —
 * anything expensive here is paid twice.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Paths.ensureDirectories(this)
        NotificationHelper.ensureChannel(this)

        // Unpack on first launch so the config editor has files to show before
        // the tunnel has ever run. The service re-checks anyway, so a failure
        // here is not fatal.
        Thread { runCatching { AssetInstaller.ensureBlocking(this) } }
            .apply { isDaemon = true }
            .start()
    }
}
