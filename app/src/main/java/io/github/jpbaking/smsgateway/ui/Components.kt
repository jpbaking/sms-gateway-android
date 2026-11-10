package io.github.jpbaking.smsgateway.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** A titled card. [action] sits at the right of the title, e.g. an Edit button. */
@Composable
internal fun Section(
    title: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            content()
        }
    }
}

/**
 * A card that shows settings read-only until Edit is tapped. While editing, nothing is applied until
 * Save; Cancel, or Back with unsaved changes, asks before throwing the changes away.
 *
 * [onSave] returns an error to show, or null if there's none. The caller ends editing (through
 * [onEditingChange]) once the changes are applied, which may be after a confirmation dialog.
 */
@Composable
internal fun EditableSection(
    title: String,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    dirty: Boolean,
    onSave: () -> String?,
    onDiscard: () -> Unit,
    saveEnabled: Boolean = true,
    view: @Composable ColumnScope.() -> Unit,
    edit: @Composable ColumnScope.() -> Unit,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun cancel() {
        if (dirty) {
            confirmDiscard = true
        } else {
            error = null
            onDiscard()
            onEditingChange(false)
        }
    }

    BackHandler(enabled = editing) { cancel() }

    Section(
        title = title,
        action = if (editing) null else ({ TextButton(onClick = {
                error = null
                onEditingChange(true)
            }) { Text("Edit") } }),
    ) {
        if (editing) {
            edit()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { cancel() }) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(enabled = saveEnabled, onClick = {
                    error = onSave()
                }) { Text("Save") }
            }
        } else {
            view()
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            text = "Your changes to $title haven't been saved.",
            confirmLabel = "Discard",
            onDismiss = { confirmDiscard = false },
            onConfirm = {
                confirmDiscard = false
                error = null
                onDiscard()
                onEditingChange(false)
            },
        )
    }
}

/** A label and its current value, for the read-only view of a settings card. */
@Composable
internal fun ValueRow(label: String, value: String, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}

/**
 * A switch with a label (and optional detail), for edit forms. With [enabled] false it can't be
 * changed; say why in [detail], which stays readable.
 */
@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    detail: String? = null,
    enabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = labelColor(enabled))
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** With [enabled] false the option can't be picked; say why in [detail], which stays readable. */
@Composable
internal fun RadioOption(
    selected: Boolean,
    title: String,
    detail: String,
    enabled: Boolean = true,
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Column {
            Text(title, color = labelColor(enabled))
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The label of a control: dimmed the way Material dims a control that is switched off. */
@Composable
private fun labelColor(enabled: Boolean) =
    MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)

/** Asks before doing something. [destructive] paints the confirm button red. */
@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Digits only, at most 5, for port and number fields. */
internal fun digits(text: String): String = text.filter(Char::isDigit).take(5)

internal fun onOff(value: Boolean): String = if (value) "On" else "Off"
