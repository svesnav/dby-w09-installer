package org.matepad.installer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { App(model) } }
    }
}

private val KubuntuBlue = Color(0xFF0079C1)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF8CC8FF), onPrimary = Color(0xFF00325A),
            primaryContainer = Color(0xFF004A80), onPrimaryContainer = Color(0xFFD1E4FF),
            background = Color(0xFF101418), surface = Color(0xFF101418),
            surfaceVariant = Color(0xFF1D2329),
        )
    } else {
        lightColorScheme(
            primary = KubuntuBlue, onPrimary = Color.White,
            primaryContainer = Color(0xFFD1E4FF), onPrimaryContainer = Color(0xFF001D36),
            background = Color(0xFFF7F9FC), surface = Color(0xFFF7F9FC),
            surfaceVariant = Color(0xFFE6EBF1),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(model: MainViewModel) {
    val s by model.state.collectAsState()
    var settings by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MatePad Linux", fontWeight = FontWeight.SemiBold)
                        Text("Kubuntu 24.04 on the microSD card", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { model.refreshTablet(); model.loadReleases() }, enabled = s.install == null) {
                        Icon(Icons.Filled.Refresh, "Refresh")
                    }
                    IconButton(onClick = { settings = true }, enabled = s.install == null) {
                        Icon(Icons.Filled.Settings, "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val wide = maxWidth > 840.dp
            val install = s.install
            if (wide) {
                Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(0.42f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        TabletCard(s, model)
                        BootCard(s, model)
                    }
                    Column(Modifier.weight(0.58f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        if (install != null) ProgressCard(install, model) else {
                            SetupCard(s, model)
                            InstallCard(s, model)
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (install != null) ProgressCard(install, model) else {
                        TabletCard(s, model)
                        BootCard(s, model)
                        SetupCard(s, model)
                        InstallCard(s, model)
                    }
                }
            }
        }
    }
    if (settings) SettingsDialog(s, onDismiss = { settings = false }) { repo, token ->
        settings = false
        model.setRepo(repo, token)
    }
}

@Composable
internal fun Section(title: String, icon: ImageVector? = null, modifier: Modifier = Modifier,
                    content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                }
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun Fact(label: String, value: String, ok: Boolean? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(120.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
        Text(value.ifBlank { "—" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (ok != null) {
            Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, null,
                tint = if (ok) Color(0xFF3FB950) else Color(0xFFE3A008), modifier = Modifier.size(20.dp))
        }
    }
}

private fun gb(bytes: Long) = if (bytes <= 0) "?" else "%.1f GB".format(bytes / 1e9)

@Composable
private fun TabletCard(s: UiState, model: MainViewModel) {
    Section("This tablet", Icons.Filled.Info) {
        val p = s.probe
        when {
            s.probing -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Checking root access and the card…")
            }
            p == null -> {
                Text(s.probeError ?: "Unknown error", color = MaterialTheme.colorScheme.error)
                Text("The installer needs root: allow MatePad Linux in your root manager (APatch: Superuser list; Magisk: the prompt), then refresh.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = model::refreshTablet) { Text("Try again") }
            }
            else -> {
                Fact("Model", "${p["model"]} (${p["device"]})", p.isMatePad)
                Fact("Root", if (p.rooted) "granted" else "missing", p.rooted)
                Fact("microSD", p.sd?.let { "${p["sd_name"]} · ${gb(p.sdBytes)}" } ?: "no card", p.sd != null)
                if (p["sd_p1_label"] == "LINUX70ROOT") Fact("On the card", "Linux (LINUX70ROOT)")
                HorizontalDivider()
                Text("Network addresses copied into Linux", style = MaterialTheme.typography.labelLarge)
                MacField("Wi-Fi MAC", s.wlanMac, optional = true, onChange = model::setWlanMac)
                MacField("Bluetooth address", s.btMac, optional = false, onChange = model::setBtMac)
                if (p["bt_mac"].isBlank()) {
                    Text("HarmonyOS did not report the Bluetooth address: copy it from Settings › About tablet › Status.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MacField(label: String, value: String, optional: Boolean, onChange: (String) -> Unit) {
    val valid = (optional && value.isBlank()) || org.matepad.installer.core.Mac.normalize(value) != null
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        isError = !valid, modifier = Modifier.fillMaxWidth(),
        supportingText = { if (!valid) Text("AA:BB:CC:DD:EE:FF") },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
    )
}

@Composable
private fun BootCard(s: UiState, model: MainViewModel) {
    var confirm by remember { mutableStateOf(false) }
    Section("Start Linux", Icons.Filled.PlayArrow) {
        val staged = s.probe?.staged == true
        Text(
            if (staged) "Linux is installed. Boot Linux writes its boot images and restarts; " +
                "Restart to HarmonyOS in Kubuntu brings you back."
            else "Install Linux first: the boot images are prepared at the end of the installation.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = { confirm = true }, enabled = staged && !s.booting && s.install == null,
            modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp),
        ) {
            if (s.booting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary)
            else Text("Boot Linux", fontSize = 18.sp)
        }
        s.bootError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Restart into Linux?") },
        text = { Text("The tablet restarts into Kubuntu on the microSD card. Keep the card inserted.") },
        confirmButton = { Button(onClick = { confirm = false; model.bootLinux() }) { Text("Restart") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable
private fun InstallCard(s: UiState, model: MainViewModel) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.pickFile(uri)
    }
    var confirm by remember { mutableStateOf(false) }
    Section("Install Linux") {
        Text("Writes Kubuntu to the whole microSD card and copies this tablet's firmware and " +
            "vendor files into it. HarmonyOS and your data stay untouched.",
            style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = s.source == SourceKind.RELEASE, onClick = { model.setSource(SourceKind.RELEASE) },
                label = { Text("GitHub release") })
            FilterChip(selected = s.source == SourceKind.FILE, onClick = { model.setSource(SourceKind.FILE) },
                label = { Text("Image file") })
        }
        when (s.source) {
            SourceKind.RELEASE -> ReleaseList(s, model)
            SourceKind.FILE -> {
                val f = s.localImage
                Fact("File", f?.name ?: "none chosen")
                if (f != null) Fact("Size", gb(f.size))
                OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text("Choose .img.xz / .img.gz") }
            }
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { confirm = true }, enabled = s.canInstall,
            modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) { Text("Erase card and install", fontSize = 18.sp) }
        if (s.probe != null && !s.macsValid) {
            Text("Enter the Bluetooth address above.", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        if (!s.setup.valid) {
            Text("Fill in your name, user name, password and time zone under Your Kubuntu.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirm) {
        var sure by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.Filled.Warning, null) },
            title = { Text("Erase the microSD card?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Everything on ${s.probe?.get("sd_name")} (${gb(s.probe?.sdBytes ?: 0)}) is deleted " +
                        "and replaced by Kubuntu. This takes 10–30 minutes; keep the tablet charging " +
                        "and this app open.")
                    Row(Modifier.clickable { sure = !sure }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = sure, onCheckedChange = { sure = it })
                        Text("I understand that the card is erased")
                    }
                }
            },
            confirmButton = {
                Button(onClick = { confirm = false; model.install() }, enabled = sure,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("Erase and install")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ReleaseList(s: UiState, model: MainViewModel) {
    when {
        s.releasesLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Loading releases of ${s.repo}…")
        }
        s.releases.isEmpty() -> {
            Text(s.releasesError ?: "No releases", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = model::loadReleases) { Text("Retry") }
                OutlinedButton(onClick = { model.setSource(SourceKind.FILE) }) { Text("Use a file instead") }
            }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.releases.take(6).forEach { r ->
                val selected = r.tag == s.selectedTag
                Surface(
                    onClick = { model.selectRelease(r.tag) }, shape = RoundedCornerShape(14.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected, onClick = { model.selectRelease(r.tag) })
                        Column(Modifier.weight(1f)) {
                            Text(r.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val m = r.manifest
                            Text(listOfNotNull(r.published, m?.let { "download ${gb(it.compressedSize)}" },
                                m?.kernel?.ifBlank { null }?.let { "Linux $it" },
                                if (r.prerelease) "pre-release" else null).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(st: InstallState, model: MainViewModel) {
    val view = LocalView.current
    DisposableEffect(st.done, st.error) {
        view.keepScreenOn = !st.done && st.error == null
        onDispose { view.keepScreenOn = false }
    }
    Section(when {
        st.error != null -> "Installation failed"
        st.done -> "Linux is installed"
        else -> "Installing…"
    }, modifier = Modifier.fillMaxSize()) {
        Step.entries.forEach { step ->
            val state = when {
                st.done || step.ordinal < st.step.ordinal -> 2
                step == st.step -> if (st.error != null) 3 else 1
                else -> 0
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    when (state) {
                        2 -> Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF3FB950))
                        1 -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        3 -> Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error)
                        else -> Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(step.label, fontWeight = if (state == 1) FontWeight.SemiBold else FontWeight.Normal)
                    if (state == 1 && st.detail.isNotBlank()) {
                        Text(st.detail, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (!st.done && st.error == null) {
            val f = st.fraction
            if (f != null) {
                LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().height(8.dp))
                Text("%.0f %%".format(f * 100), style = MaterialTheme.typography.bodySmall)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp))
            }
        }
        st.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
            Text("HarmonyOS's boot partitions were not changed. Fix the cause and install again.",
                style = MaterialTheme.typography.bodyMedium)
        }
        val listState = rememberLazyListState()
        LaunchedEffect(st.log.size) { if (st.log.isNotEmpty()) listState.scrollToItem(st.log.size - 1) }
        Surface(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp),
            shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            LazyColumn(Modifier.padding(12.dp), state = listState) {
                items(st.log) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        }
        if (st.done || st.error != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (st.done) Button(onClick = model::bootLinux, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(52.dp)) { Text("Boot Linux now", fontSize = 16.sp) }
                OutlinedButton(onClick = model::closeInstall, modifier = Modifier.height(52.dp)) { Text("Close") }
            }
        }
    }
}

@Composable
private fun SettingsDialog(s: UiState, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var repo by remember { mutableStateOf(s.repo) }
    var token by remember { mutableStateOf(s.token) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Image source") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(repo, { repo = it }, label = { Text("GitHub repository") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, label = { Text("Access token (private repositories)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
                Text("Public repositories need no token. A fine-grained token with read access to the " +
                    "repository's contents is enough for a private one; it stays on this tablet.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { onSave(repo, token) }) { Text("Save") } },
        dismissButton = {
            TextButton(onClick = { repo = UiState.DEFAULT_REPO }) { Text("Default") }
        },
    )
}
