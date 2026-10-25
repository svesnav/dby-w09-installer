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

data class InstallState(
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
) {
    val selectedRelease get() = releases.firstOrNull { it.tag == selectedTag }
    val macsValid get() = (wlanMac.isBlank() || Mac.normalize(wlanMac) != null) && Mac.normalize(btMac) != null
    val canInstall
        get() = probe?.isMatePad == true && probe.sd != null && install == null && macsValid &&
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
        _state.update { it.copy(install = InstallState()) }
        viewModelScope.launch(Dispatchers.IO) {
            val installer = Installer(app) { r ->
                _state.update { st ->
                    val cur = st.install ?: InstallState()
                    st.copy(
                        install = cur.copy(
                            step = r.step,
                            fraction = if (r.log == null) r.fraction else cur.fraction,
                            detail = if (r.log == null) r.detail else cur.detail,
                            log = if (r.log != null) (cur.log + r.log).takeLast(400) else cur.log,
                        )
                    )
                }
            }
            try {
                installer.install(source, Mac.normalize(s.wlanMac).orEmpty(), Mac.normalize(s.btMac).orEmpty())
                _state.update { it.copy(install = it.install?.copy(done = true, fraction = 1f, detail = "Done")) }
            } catch (e: Exception) {
                _state.update { it.copy(install = it.install?.copy(error = e.message ?: e.toString())) }
            }
            refreshTablet()
        }
    }

    fun closeInstall() = _state.update { it.copy(install = null) }

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
