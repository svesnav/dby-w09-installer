package org.matepad.installer

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matepad.installer.core.Mac

enum class SourceKind { RELEASE, FILE }

data class LocalImage(val uri: Uri, val name: String, val size: Long)

/** What the session does: install Linux on the card, or update the installed one. */
enum class Job(val steps: List<Step>) {
    INSTALL(listOf(Step.CHECK, Step.CARD, Step.WRITE, Step.FILES, Step.SETUP, Step.BOOT)),
    UPDATE(listOf(Step.CHECK, Step.UPDATE, Step.BOOT)),
}

data class InstallState(
    val job: Job = Job.INSTALL,
    val step: Step = Step.CHECK,
    val fraction: Float? = null,
    val detail: String = "",
    val log: List<String> = emptyList(),
    val error: String? = null,
    val done: Boolean = false,
)

data class UiState(
    val probing: Boolean = true,
    val probe: Probe? = null,
    val probeError: String? = null,
    val wlanMac: String = "",
    val btMac: String = "",
    val repo: String = DEFAULT_REPO,
    val token: String = "",
    val source: SourceKind = SourceKind.RELEASE,
    val releases: List<Release> = emptyList(),
    val releasesLoading: Boolean = false,
    val releasesError: String? = null,
    val selectedTag: String? = null,
    val localImage: LocalImage? = null,
    val install: InstallState? = null,
    val bootError: String? = null,
    val booting: Boolean = false,
    val setup: SystemSetup = SystemSetup(),
) {
    val selectedRelease get() = releases.firstOrNull { it.tag == selectedTag }
    val macsValid get() = (wlanMac.isBlank() || Mac.normalize(wlanMac) != null) && Mac.normalize(btMac) != null
    val canUpdate get() = probe?.linuxInstalled == true && install == null
    val canInstall
        get() = probe?.isMatePad == true && probe.sd != null && install == null && macsValid && setup.valid &&
            when (source) {
                SourceKind.RELEASE -> selectedRelease?.parts != null
                SourceKind.FILE -> localImage != null
            }

    companion object {
        const val DEFAULT_REPO = "svesnav/kubuntu-dby-w09"
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        UiState(
            // The image repository was renamed from ubuntu-dby-w09.
            repo = prefs.getString("repo", null)?.replace("/ubuntu-dby-w09", "/kubuntu-dby-w09")
                ?: UiState.DEFAULT_REPO,
            token = prefs.getString("token", null).orEmpty(),
        )
    )
    val state: StateFlow<UiState> = _state

    init {
        refreshTablet()
        loadReleases()
        // The installation belongs to the process, not to this screen: show it
        // again after the app is reopened, and refresh the tablet when it ends.
        viewModelScope.launch {
            var ended = false
            InstallSession.state.collect { st ->
                _state.update { it.copy(install = st) }
                val nowEnded = st != null && (st.done || st.error != null)
                if (nowEnded && !ended) refreshTablet()
                ended = nowEnded
            }
        }
    }

    fun refreshTablet() {
        _state.update { it.copy(probing = true, probeError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val p = Installer.probe(getApplication())
                _state.update {
                    it.copy(
                        probing = false, probe = p,
                        wlanMac = it.wlanMac.ifBlank { Mac.normalize(p["wlan_mac"]).orEmpty() },
                        btMac = it.btMac.ifBlank { Mac.normalize(p["bt_mac"]).orEmpty() },
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(probing = false, probeError = e.message ?: e.toString()) }
            }
        }
    }

    fun loadReleases() {
        val s = _state.value
        _state.update { it.copy(releasesLoading = true, releasesError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = GitHub(s.repo, s.token).releases()
                _state.update { st ->
                    st.copy(
                        releasesLoading = false, releases = list,
                        selectedTag = st.selectedTag?.takeIf { t -> list.any { it.tag == t } }
                            ?: list.firstOrNull { !it.prerelease }?.tag ?: list.firstOrNull()?.tag,
                        releasesError = if (list.isEmpty()) "No image releases in ${s.repo}" else null,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(releasesLoading = false, releasesError = e.message ?: e.toString()) }
            }
        }
    }

    fun setRepo(repo: String, token: String) {
        prefs.edit().putString("repo", repo.trim()).putString("token", token.trim()).apply()
        _state.update { it.copy(repo = repo.trim(), token = token.trim(), releases = emptyList()) }
        loadReleases()
    }

    fun setSource(kind: SourceKind) = _state.update { it.copy(source = kind) }
    fun selectRelease(tag: String) = _state.update { it.copy(selectedTag = tag) }
    /** The user name follows the full name until it is edited by hand. */
    fun setFullName(v: String) = _state.update {
        val s = it.setup
        val follows = s.userName.isEmpty() || s.userName == SystemSetup.suggestUserName(s.fullName)
        it.copy(setup = s.copy(fullName = v, userName = if (follows) SystemSetup.suggestUserName(v) else s.userName))
    }
    fun updateSetup(change: (SystemSetup) -> SystemSetup) = _state.update { it.copy(setup = change(it.setup)) }
    fun setWlanMac(v: String) = _state.update { it.copy(wlanMac = v) }
    fun setBtMac(v: String) = _state.update { it.copy(btMac = v) }

    fun pickFile(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        var name = uri.lastPathSegment ?: "image"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        _state.update { it.copy(source = SourceKind.FILE, localImage = LocalImage(uri, name, size)) }
    }

    fun install() {
        val s = _state.value
        if (!s.canInstall) return
        val app = getApplication<Application>()
        val source = when (s.source) {
            SourceKind.RELEASE -> ImageSource.FromRelease(GitHub(s.repo, s.token), s.selectedRelease!!)
            SourceKind.FILE -> s.localImage!!.let { f ->
                ImageSource.FromFile(f.name, f.size) {
                    app.contentResolver.openInputStream(f.uri) ?: throw java.io.IOException("cannot open ${f.name}")
                }
            }
        }
        val wlan = Mac.normalize(s.wlanMac).orEmpty()
        val bt = Mac.normalize(s.btMac).orEmpty()
        InstallSession.start(app, Job.INSTALL) { it.install(source, wlan, bt, s.setup) }
    }

    /** Updates Kubuntu on the card (apt) and prepares its boot images again. */
    fun update() {
        val s = _state.value
        if (!s.canUpdate) return
        InstallSession.start(getApplication<Application>(), Job.UPDATE) { it.update() }
    }

    fun closeInstall() = InstallSession.clear()

    fun bootLinux() {
        _state.update { it.copy(booting = true, bootError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val r = try {
                Installer.bootLinux()
            } catch (e: Exception) {
                Root.Result(1, "", e.message ?: e.toString())
            }
            // On success the tablet is restarting; only a failure gets here in time.
            _state.update { it.copy(booting = false, bootError = if (r.ok) null else r.message()) }
        }
    }
}
