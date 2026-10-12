package com.example.coach_android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.coach_android.data.model.AppRule

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailSheet(
    appName: String,
    packageName: String,
    isCoached: Boolean,
    rules: List<AppRule>,
    ruleCounters: Map<String, Pair<Int, Int>>,
    onCoachToggle: (Boolean) -> Unit,
    onAddRule: () -> Unit,
    onEditRule: (AppRule) -> Unit,
    onDeleteRule: (AppRule) -> Unit,
    onResetRule: (AppRule) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
        ) {
            Text(
                text = appName,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            // Coach toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Coach overlay", style = MaterialTheme.typography.bodyLarge)
                Checkbox(checked = isCoached, onCheckedChange = onCoachToggle)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Rules section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Rules", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onAddRule) {
                    Icon(Icons.Default.Add, contentDescription = "Add rule")
                }
            }

            if (rules.isEmpty()) {
                Text(
                    text = "No rules configured",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                rules.forEach { rule ->
                    val counters = ruleCounters[rule.id]
                    Card(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Every ${rule.everyN} open${if (rule.everyN != 1) "s" else ""}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "Max ${rule.maxTriggers}/day • Challenge: ${rule.challengeType}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (counters != null) {
                                Text(
                                    text = "Opens: ${counters.first} • Triggers: ${counters.second}/${rule.maxTriggers}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = { onResetRule(rule) }) {
                                    Text("Reset", style = MaterialTheme.typography.labelSmall)
                                }
                                IconButton(onClick = { onEditRule(rule) }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { onDeleteRule(rule) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
