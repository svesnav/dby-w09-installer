package org.matepad.installer

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The one installation of this app process. It outlives the screen (leaving
 * the app does not stop it, reopening shows it again), and a second one
 * cannot start while it runs: two would write the same card.
 */
object InstallSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<InstallState?>(null)
    val state: StateFlow<InstallState?> = _state

    val running: Boolean
        get() = _state.value.let { it != null && !it.done && it.error == null }

    @Synchronized
    fun start(app: Application, source: ImageSource, wlanMac: String, btMac: String): Boolean {
        if (running) return false
        _state.value = InstallState()
        scope.launch {
            val installer = Installer(app) { r ->
                _state.update { cur ->
                    val st = cur ?: InstallState()
                    st.copy(
                        step = r.step,
                        fraction = if (r.log == null) r.fraction else st.fraction,
                        detail = if (r.log == null) r.detail else st.detail,
                        log = if (r.log != null) (st.log + r.log).takeLast(400) else st.log,
                    )
                }
            }
            try {
                installer.install(source, wlanMac, btMac)
                _state.update { it?.copy(done = true, fraction = 1f, detail = "Done") }
            } catch (e: Exception) {
                _state.update { it?.copy(error = e.message ?: e.toString()) }
            }
        }
        return true
    }

    /** Forgets a finished installation (the Close button); a running one stays. */
    @Synchronized
    fun clear() {
        if (!running) _state.value = null
    }
}
