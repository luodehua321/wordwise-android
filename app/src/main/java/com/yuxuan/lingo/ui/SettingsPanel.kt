package com.yuxuan.lingo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** All local preferences share one section; changing pronunciation does not restart a word. */
@Composable
internal fun SettingsPanel(feedback: FeedbackUi, onAction: (AppAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surface, shadowElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)) {
                Icon(Icons.Outlined.Tune, null, Modifier.size(19.dp), tint = colors.primary)
                Spacer(Modifier.width(9.dp))
                Text("设置", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
                Spacer(Modifier.weight(1f))
                Text("本地偏好", fontSize = 9.sp, color = colors.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)) {
                Icon(Icons.Outlined.Translate, null, Modifier.size(21.dp), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(13.dp))
                Column {
                    Text("音标偏好", fontSize = 14.sp, color = colors.onSurface)
                    Text("应用于学习、易错本和单词详情", fontSize = 11.sp, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentOption("美式音标", feedback.pronunciationAccent == PronunciationAccent.US, Modifier.weight(1f)) {
                    onAction(AppAction.SetPronunciationAccent(PronunciationAccent.US))
                }
                AccentOption("英式音标", feedback.pronunciationAccent == PronunciationAccent.UK, Modifier.weight(1f)) {
                    onAction(AppAction.SetPronunciationAccent(PronunciationAccent.UK))
                }
            }
            HorizontalDivider(color = colors.outlineVariant, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
            SettingsToggleRow("操作音效", "切换、答题与完成时轻声提示", Icons.Outlined.VolumeUp, feedback.soundEnabled) {
                onAction(AppAction.SetSoundEnabled(it))
            }
            HorizontalDivider(color = colors.outlineVariant)
            SettingsToggleRow("触感反馈", "轻触、答题与完成时短暂震动", Icons.Outlined.Vibration, feedback.hapticsEnabled) {
                onAction(AppAction.SetHapticsEnabled(it))
            }
            Text("音效使用媒体音量；触感需要设备具有振动器。设置保存在当前设备，系统勿扰策略可能限制反馈。",
                fontSize = 10.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        }
    }
}

@Composable
private fun AccentOption(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.heightIn(min = 48.dp)
        .clip(CircleShape)
        .background(if (selected) colors.primaryContainer else colors.surfaceContainerLow)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = 6.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.size(22.dp),
            colors = RadioButtonDefaults.colors(selectedColor = colors.primary))
        Text(label, fontSize = 13.sp, color = colors.onSurface, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun SettingsToggleRow(
    title: String, subtitle: String, icon: ImageVector,
    enabled: Boolean, onChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
        .toggleable(value = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(21.dp), tint = colors.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
            Text(title, fontSize = 14.sp, color = colors.onSurface)
            Text(subtitle, fontSize = 11.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
        }
        Switch(checked = enabled, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedThumbColor = colors.onPrimary,
                checkedTrackColor = colors.primary, uncheckedThumbColor = colors.surface,
                uncheckedTrackColor = colors.surfaceContainerHighest,
                uncheckedBorderColor = colors.outlineVariant))
    }
}
