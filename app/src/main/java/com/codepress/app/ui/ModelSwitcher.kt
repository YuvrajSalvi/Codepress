package com.codepress.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codepress.app.data.ConfiguredModel
import com.codepress.app.data.ProviderType

/**
 * Chip above the message box. Lists only the models the user has already set up (key + model name),
 * so running out of quota on one provider is a single tap away from continuing on another.
 */
@Composable
fun ModelSwitcher(vm: ChatViewModel, enabled: Boolean, onManage: () -> Unit, modifier: Modifier = Modifier) {
    val rev by vm.revision.collectAsStateWithLifecycle()
    val entries = remember(rev) { vm.settings.configuredModels() }
    val active = remember(rev) { vm.settings.provider }
    val openAiBase = remember(rev) { vm.settings.openAiBaseUrl }
    var open by remember { mutableStateOf(false) }
    val current = entries.firstOrNull { it.provider == active }
    // Every model the user can pick without opening Settings: the saved model plus the provider's loaded model list.
    val options = remember(rev) {
        entries.flatMap { e ->
            (listOf(e.model) + vm.settings.cachedModels(e.provider)).distinct().map { ConfiguredModel(e.provider, e.label, it) }
        }
    }
    val switchable = options.size > 1
    val shape = RoundedCornerShape(20.dp)

    Box(modifier) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = shape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.clip(shape).clickable {
                if (entries.isEmpty()) onManage() else if (switchable && enabled) open = true
            },
        ) {
            Row(Modifier.padding(start = 8.dp, end = if (switchable) 6.dp else 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                current?.let { ProviderIcon(providerIconRes(it.provider, openAiBase), 18.dp); androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 6.dp)) }
                Text(
                    current?.title ?: "Add a model",
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (enabled || !switchable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 260.dp),
                )
                if (switchable) Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Switch model", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { e ->
                val selected = e.provider == active && e.model == vm.settings.model(e.provider)
                DropdownMenuItem(
                    leadingIcon = { ProviderIcon(providerIconRes(e.provider, openAiBase), 20.dp) },
                    text = { Text(if (e.provider == ProviderType.LOCAL) e.model else "${e.label} · ${e.model}" + if (selected) "  ✓" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        if (e.provider != ProviderType.LOCAL) vm.settings.setModel(e.provider, e.model)
                        vm.switchModel(e.provider)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Manage models and keys") }, onClick = { open = false; onManage() })
        }
    }
}
