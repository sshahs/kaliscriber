package com.kaliscriber.app.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import com.kaliscriber.app.ui.common.SectionCard
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.ThemeMode

/** Light or dark, and whether colours come from the wallpaper. */
@Composable
internal fun AppearanceSection(settings: AppSettings, vm: SettingsViewModel) {
    SectionCard("Appearance") {
        Column(Modifier.selectableGroup()) {
            ThemeMode.entries.forEach { mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = settings.themeMode == mode,
                            onClick = { vm.update { it.copy(themeMode = mode) } },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The row handles the click, so the button itself is left out of accessibility.
                    RadioButton(selected = settings.themeMode == mode, onClick = null)
                    Text(mode.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ToggleRow("Colours from your wallpaper", "Otherwise KaliScriber uses its own indigo colours.", settings.dynamicColor) { v ->
                vm.update { it.copy(dynamicColor = v) }
            }
        }
    }
}
