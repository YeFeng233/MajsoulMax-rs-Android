package moe.majsoulmax.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.majsoulmax.app.core.AssetInstaller
import moe.majsoulmax.app.core.CertManager
import moe.majsoulmax.app.core.MihomoKernel
import moe.majsoulmax.app.core.MitmNative
import moe.majsoulmax.app.core.Tun2SocksNative
import moe.majsoulmax.app.data.TunnelStatus
import moe.majsoulmax.app.service.NotificationHelper

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * Each check is something that will break the tunnel at start-up if it is
     * false, which is why they are surfaced before the switch rather than as an
     * error afterwards.
     */
    data class Checks(
        val assetsReady: Boolean = false,
        val certTrusted: Boolean = false,
        val kernelBundled: Boolean = false,
        val coreBundled: Boolean = false,
        val tunnelBundled: Boolean = false,
        val notificationsAllowed: Boolean = false,
        val loading: Boolean = true,
    ) {
        /** Missing notification access degrades reliability but does not block. */
        val blocking: Boolean
            get() = !assetsReady || !kernelBundled || !coreBundled || !tunnelBundled
    }

    val status: StateFlow<TunnelStatus> =
        TunnelStatus.observe(application).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TunnelStatus.read(application),
        )

    /** Drives the uptime readout without re-reading status.json every second. */
    val ticker: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            kotlinx.coroutines.delay(1_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), System.currentTimeMillis())

    private val _checks = MutableStateFlow(Checks())
    val checks: StateFlow<Checks> = _checks.asStateFlow()

    val coreVersion: String get() = MitmNative.version

    init {
        refreshChecks()
    }

    fun refreshChecks() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            _checks.value = _checks.value.copy(loading = true)

            // Unpack before checking, so a first launch shows green rather than a
            // red row the user cannot act on.
            AssetInstaller.ensure(context)

            val certTrusted = CertManager.isTrusted(context)
            val result = withContext(Dispatchers.IO) {
                Checks(
                    assetsReady = AssetInstaller.isInstalled(context),
                    certTrusted = certTrusted,
                    kernelBundled = MihomoKernel.isBundled(context),
                    coreBundled = MitmNative.available,
                    tunnelBundled = Tun2SocksNative.available,
                    notificationsAllowed = NotificationHelper.hasPermission(context),
                    loading = false,
                )
            }
            _checks.value = result
        }
    }
}
