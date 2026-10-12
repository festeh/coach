package com.example.coach_android.ui.apps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.coach_android.data.model.AppInfo
import com.example.coach_android.data.model.AppRule
import com.example.coach_android.ui.components.AgentLockCard
import com.example.coach_android.ui.components.AppDetailSheet
import com.example.coach_android.ui.components.FocusStatusCard
import com.example.coach_android.ui.components.RuleEditorDialog

@Composable
fun AppsScreen(
    viewModel: AppsViewModel = viewModel(),
    onOpenChat: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }
    var editingRule by remember { mutableStateOf<AppRule?>(null) }
    var showRuleEditor by remember { mutableStateOf(false) }
    var ruleEditorPackage by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }

    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Sort: selected apps first, then alphabetically
    val sortedApps =
        remember(state.apps, state.selectedPackages) {
            state.apps.sortedWith(
                compareByDescending<AppInfo> { state.selectedPackages.contains(it.packageName) }
                    .thenBy { it.name.lowercase() },
            )
        }

    val filteredApps =
        remember(sortedApps, searchQuery) {
            if (searchQuery.isBlank()) {
                sortedApps
            } else {
                sortedApps.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Focus status card
        item {
            FocusStatusCard(
                focusData = state.focusData,
                isConnected = state.isConnected,
                focusDurationMinutes = state.focusDurationMinutes,
                onFocusClick = viewModel::sendFocusCommand,
                onRefreshClick = viewModel::refreshFocusState,
            )
            Spacer(Modifier.height(8.dp))
        }

        // Agent lock indicator
        item {
            AgentLockCard(focusData = state.focusData, onOpenChat = onOpenChat)
            Spacer(Modifier.height(8.dp))
        }

        // Search bar
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
            )
        }

        // App list
        items(filteredApps, key = { it.packageName }) { app ->
            val isCoached = state.selectedPackages.contains(app.packageName)
            val hasRules = state.rules.values.any { it.packageName == app.packageName }

            AppListItem(
                app = app,
                isCoached = isCoached,
                hasRules = hasRules,
                onClick = { selectedApp = app },
            )
        }
    }

    // Bottom sheet
    selectedApp?.let { app ->
        AppDetailSheet(
            appName = app.name,
            packageName = app.packageName,
            isCoached = state.selectedPackages.contains(app.packageName),
            rules = viewModel.rulesForPackage(app.packageName),
            ruleCounters = state.ruleCounters,
            onCoachToggle = { enabled -> viewModel.toggleCoach(app.packageName, enabled) },
            onAddRule = {
                editingRule = null
                ruleEditorPackage = app.packageName
                showRuleEditor = true
            },
            onEditRule = { rule ->
                editingRule = rule
                ruleEditorPackage = app.packageName
                showRuleEditor = true
            },
            onDeleteRule = { rule -> viewModel.deleteRule(rule) },
            onResetRule = { rule -> viewModel.resetRule(rule) },
            onDismiss = { selectedApp = null },
        )
    }

    // Rule editor dialog
    if (showRuleEditor) {
        RuleEditorDialog(
            rule = editingRule,
            packageName = ruleEditorPackage,
            onDismiss = { showRuleEditor = false },
            onSave = { rule ->
                viewModel.saveRule(rule)
                showRuleEditor = false
            },
        )
    }
}

@Composable
private fun AppListItem(
    app: AppInfo,
    isCoached: Boolean,
    hasRules: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (isCoached) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(
                        text = "Coach",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            if (hasRules) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Text(
                        text = "Rules",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
