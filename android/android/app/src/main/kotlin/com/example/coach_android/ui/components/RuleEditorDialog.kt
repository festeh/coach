package com.example.coach_android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.coach_android.data.model.AppRule

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorDialog(
    rule: AppRule?,
    packageName: String,
    onDismiss: () -> Unit,
    onSave: (AppRule) -> Unit,
) {
    val isNew = rule == null
    var everyN by remember { mutableIntStateOf(rule?.everyN ?: 1) }
    var maxTriggers by remember { mutableIntStateOf(rule?.maxTriggers ?: 10) }
    var challengeType by remember { mutableStateOf(rule?.challengeType ?: "none") }
    var challengeExpanded by remember { mutableStateOf(false) }

    val challengeOptions = listOf("none", "longPress", "typing")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add Rule" else "Edit Rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Every N opens
                Text("Show every N opens", style = MaterialTheme.typography.labelMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { if (everyN > 1) everyN-- },
                        enabled = everyN > 1,
                    ) { Text("-") }
                    Text(
                        "$everyN",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    OutlinedButton(onClick = { everyN++ }) { Text("+") }
                }

                // Max triggers per day
                Text("Max triggers per day", style = MaterialTheme.typography.labelMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { if (maxTriggers > 1) maxTriggers-- },
                        enabled = maxTriggers > 1,
                    ) { Text("-") }
                    Text(
                        "$maxTriggers",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    OutlinedButton(onClick = { maxTriggers++ }) { Text("+") }
                }

                // Challenge type
                Text("Challenge type", style = MaterialTheme.typography.labelMedium)
                ExposedDropdownMenuBox(
                    expanded = challengeExpanded,
                    onExpandedChange = { challengeExpanded = it },
                ) {
                    OutlinedTextField(
                        value = challengeType,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = challengeExpanded) },
                    )
                    ExposedDropdownMenu(
                        expanded = challengeExpanded,
                        onDismissRequest = { challengeExpanded = false },
                    ) {
                        challengeOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    challengeType = option
                                    challengeExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val saved =
                    AppRule(
                        id =
                            rule?.id ?: java.util.UUID
                                .randomUUID()
                                .toString(),
                        packageName = packageName,
                        everyN = everyN,
                        maxTriggers = maxTriggers,
                        challengeType = challengeType,
                    )
                onSave(saved)
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
