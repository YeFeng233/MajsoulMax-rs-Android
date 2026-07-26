package moe.majsoulmax.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Backs the notification's "stop" action. */
class TileActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) {
            TunnelController.stop(context)
        }
    }

    companion object {
        const val ACTION_STOP = "moe.majsoulmax.app.action.STOP"
    }
}
