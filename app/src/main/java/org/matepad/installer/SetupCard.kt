package org.matepad.installer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** What Kubuntu's first-boot setup would ask, answered here before installing. */
@Composable
fun SetupCard(s: UiState, model: MainViewModel) {
    val st = s.setup
    Section("Your Kubuntu", Icons.Filled.Person) {
        Text("The installer sets these up on the card, so Kubuntu starts at its login screen.",
            style = MaterialTheme.typography.bodyMedium)
        Field("Your name", st.fullName, model::setFullName, capitalize = true)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("User name", st.userName, { v -> model.updateSetup { it.copy(userName = v.trim()) } },
                error = st.userNameError, modifier = Modifier.weight(1f))
            Field("Computer name", st.hostname, { v -> model.updateSetup { it.copy(hostname = v.trim()) } },
                error = st.hostnameError, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Password", st.password, { v -> model.updateSetup { it.copy(password = v) } },
                password = true, modifier = Modifier.weight(1f))
            Field("Password again", st.password2, { v -> model.updateSetup { it.copy(password2 = v) } },
                password = true, error = st.passwordError, modifier = Modifier.weight(1f))
        }
        Choice("Language", st.language.label, LANGUAGES.map { it.label }) { i ->
            val lang = LANGUAGES[i]
            model.updateSetup {
                it.copy(language = lang,
                    keyboard = KEYBOARD_LAYOUTS.firstOrNull { k -> k.xkb == lang.keyboard } ?: it.keyboard)
            }
        }
        Choice("Keyboard layout", st.keyboard.label, KEYBOARD_LAYOUTS.map { it.label }) { i ->
            model.updateSetup { it.copy(keyboard = KEYBOARD_LAYOUTS[i]) }
        }
        TimeZoneChoice(st.timezone) { tz -> model.updateSetup { it.copy(timezone = tz) } }
    }
}


@Composable
private fun Field(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(),
    error: String? = null, password: Boolean = false, capitalize: Boolean = false,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        isError = error != null, supportingText = if (error != null) ({ Text(error) }) else null,
        visualTransformation = if (password) PasswordVisualTransformation() else
            VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
            capitalization = if (capitalize) KeyboardCapitalization.Words else KeyboardCapitalization.None,
            autoCorrectEnabled = false,
        ),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Choice(label: String, value: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, label = { Text(label) }, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(i) })
            }
        }
    }
}

/** Area/City zones, filtered as you type. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeZoneChoice(value: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember(value) { mutableStateOf(value) }
    val matches = remember(query) {
        val q = query.trim().replace(' ', '_')
        (if (q == value) TIME_ZONES else TIME_ZONES.filter { it.contains(q, ignoreCase = true) }).take(60)
    }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = query, onValueChange = { query = it; open = true }, label = { Text("Time zone") },
            singleLine = true, isError = query.trim() != value,
            supportingText = if (query.trim() != value) ({ Text("pick a zone from the list") }) else null,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open && matches.isNotEmpty(), onDismissRequest = { open = false }) {
            matches.forEach { tz ->
                DropdownMenuItem(text = { Text(tz.replace('_', ' ')) }, onClick = {
                    open = false; query = tz; onPick(tz)
                })
            }
        }
    }
}
