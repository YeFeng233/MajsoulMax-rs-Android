package moe.majsoulmax.app.ui.config

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import moe.majsoulmax.app.core.AssetInstaller
import moe.majsoulmax.app.data.ConfigRepository
import moe.majsoulmax.app.data.TunnelSettings
import moe.majsoulmax.app.data.TunnelSettingsStore
import moe.majsoulmax.app.data.TunnelStatus
import moe.majsoulmax.app.service.TunnelController

/**
 * Backs the config editor.
 *
 * Edits accumulate as a *patch* keyed by JSON field rather than as a rewritten
 * document. Two things fall out of that: fields the UI does not model (upstream's
 * `viewsPresets`, or anything a future release adds) are never touched, and a
 * save cannot revert `liqiVersion`/`version` if the Rust core bumped them while
 * this screen was open.
 */
class ConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ConfigRepository(application)
    private val tunnelStore = TunnelSettingsStore.get(application)

    data class State(
        val general: JsonObject = EMPTY,
        val mod: JsonObject = EMPTY,
        val generalPatch: Map<String, JsonElement> = emptyMap(),
        val modPatch: Map<String, JsonElement> = emptyMap(),
        val loading: Boolean = true,
    ) {
        /** What the form fields should display: disk state with edits applied. */
        val effectiveGeneral: JsonObject
            get() = if (generalPatch.isEmpty()) general else merge(general, generalPatch)

        val effectiveMod: JsonObject
            get() = if (modPatch.isEmpty()) mod else merge(mod, modPatch)

        fun isDirty(which: ConfigRepository.Which): Boolean = when (which) {
            ConfigRepository.Which.GENERAL -> generalPatch.isNotEmpty()
            ConfigRepository.Which.MOD -> modPatch.isNotEmpty()
        }

        private fun merge(base: JsonObject, patch: Map<String, JsonElement>) =
            JsonObject(base.toMutableMap().apply { putAll(patch) })

        companion object {
            private val EMPTY = JsonObject(emptyMap())
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val tunnelSettings: StateFlow<TunnelSettings> = tunnelStore.settings

    /** Drives the "restart to apply" banner. */
    val status: StateFlow<TunnelStatus> = TunnelStatus.observe(application).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TunnelStatus.read(application),
    )

    /**
     * Config is read at start-up only, so applying an edit means a full restart.
     * The `:core` process exits on stop, so we wait for STOPPED before starting
     * again rather than racing the two.
     */
    fun restartTunnel() {
        val application = getApplication<Application>()
        val wasProxyOnly = status.value.proxyOnly
        viewModelScope.launch {
            TunnelController.stop(application)
            withTimeoutOrNull(RESTART_TIMEOUT_MS) {
                while (TunnelStatus.read(application).stage != TunnelStatus.Stage.STOPPED) {
                    delay(200)
                }
            }
            if (wasProxyOnly) {
                TunnelController.startProxyOnly(application)
            } else {
                TunnelController.start(application)
            }
        }
    }

    /** One-shot user-facing messages, consumed by the screen's snackbar. */
    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            AssetInstaller.ensure(getApplication())
            val general = runCatching { repository.load(ConfigRepository.Which.GENERAL) }
                .getOrElse { JsonObject(emptyMap()) }
            val mod = runCatching { repository.load(ConfigRepository.Which.MOD) }
                .getOrElse { JsonObject(emptyMap()) }
            _state.value = State(general = general, mod = mod, loading = false)
        }
    }

    fun edit(which: ConfigRepository.Which, key: String, value: JsonElement) {
        _state.value = when (which) {
            ConfigRepository.Which.GENERAL ->
                _state.value.copy(generalPatch = _state.value.generalPatch + (key to value))

            ConfigRepository.Which.MOD ->
                _state.value.copy(modPatch = _state.value.modPatch + (key to value))
        }
    }

    fun discard(which: ConfigRepository.Which) {
        _state.value = when (which) {
            ConfigRepository.Which.GENERAL -> _state.value.copy(generalPatch = emptyMap())
            ConfigRepository.Which.MOD -> _state.value.copy(modPatch = emptyMap())
        }
    }

    fun save(which: ConfigRepository.Which, savedMessage: String, errorFormat: String) {
        val patch = when (which) {
            ConfigRepository.Which.GENERAL -> _state.value.generalPatch
            ConfigRepository.Which.MOD -> _state.value.modPatch
        }
        if (patch.isEmpty()) return

        viewModelScope.launch {
            try {
                val merged = repository.patch(which, patch)
                _state.value = when (which) {
                    ConfigRepository.Which.GENERAL ->
                        _state.value.copy(general = merged, generalPatch = emptyMap())

                    ConfigRepository.Which.MOD ->
                        _state.value.copy(mod = merged, modPatch = emptyMap())
                }
                _messages.value = savedMessage
            } catch (e: Exception) {
                _messages.value = errorFormat.format(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun resetToDefaults(which: ConfigRepository.Which, savedMessage: String) {
        viewModelScope.launch {
            runCatching { repository.reset(which) }
                .onSuccess { fresh ->
                    _state.value = when (which) {
                        ConfigRepository.Which.GENERAL ->
                            _state.value.copy(general = fresh, generalPatch = emptyMap())

                        ConfigRepository.Which.MOD ->
                            _state.value.copy(mod = fresh, modPatch = emptyMap())
                    }
                    _messages.value = savedMessage
                }
                .onFailure { _messages.value = it.message }
        }
    }

    // -- Raw JSON editor ----------------------------------------------------

    suspend fun loadRaw(which: ConfigRepository.Which): String = repository.loadRaw(which)

    fun validate(text: String): String? = repository.validate(text)

    fun format(text: String): String = runCatching { repository.prettyPrint(text) }.getOrDefault(text)

    fun saveRaw(
        which: ConfigRepository.Which,
        text: String,
        savedMessage: String,
        errorFormat: String,
    ) {
        val problem = validate(text)
        if (problem != null) {
            _messages.value = errorFormat.format(problem)
            return
        }
        viewModelScope.launch {
            try {
                repository.writeRaw(which, text)
                // The typed forms are now stale, so pull everything back in.
                reload()
                _messages.value = savedMessage
            } catch (e: Exception) {
                _messages.value = errorFormat.format(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // -- Proxy / tunnel settings -------------------------------------------

    fun updateTunnel(transform: (TunnelSettings) -> TunnelSettings) {
        viewModelScope.launch { tunnelStore.update(transform) }
    }

    fun consumeMessage() {
        _messages.value = null
    }

    private companion object {
        const val RESTART_TIMEOUT_MS = 8_000L
    }
}
