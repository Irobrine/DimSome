package com.mono.appdimmer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mono.appdimmer.MainUiState
import com.mono.appdimmer.R
import com.mono.appdimmer.data.InstalledApp
import com.mono.appdimmer.data.OverlayEngine
import kotlin.math.roundToInt

private val ReadyGreen = Color(0xFF7CE0A3)
private val MutedText = Color(0xFFA8ACB8)

@Composable
fun AppDimmerScreen(
    state: MainUiState,
    onToggleEnabled: (Boolean) -> Unit,
    onEngineChange: (OverlayEngine) -> Unit,
    onOpacityChange: (Int) -> Unit,
    onToggleApp: (String) -> Unit,
    onClearSelection: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestUsageAccess: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onDismissAccessibilityDialog: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    val setupNeeded = when (state.engine) {
        OverlayEngine.ACCESSIBILITY -> !state.accessibilityEnabled
        OverlayEngine.COMPATIBILITY -> !state.overlayGranted || !state.usageAccessGranted
    }

    // The setup card is inserted/removed at the top, so reset the scroll when
    // the permission state changes instead of leaving it hidden above the fold.
    LaunchedEffect(setupNeeded, state.engine) {
        listState.scrollToItem(0)
    }

    val filteredApps = remember(state.apps, query) {
        if (query.isBlank()) {
            state.apps
        } else {
            state.apps.filter { app ->
                app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Header(
                state = state,
                onToggleEnabled = onToggleEnabled,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 6.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "mode") {
                OverlayModeCard(
                    engine = state.engine,
                    onEngineChange = onEngineChange,
                )
            }

            if (setupNeeded) {
                item(key = "setup") {
                    SetupCard(
                        state = state,
                        onRequestOverlayPermission = onRequestOverlayPermission,
                        onRequestUsageAccess = onRequestUsageAccess,
                        onRequestAccessibility = onRequestAccessibility,
                    )
                }
            }

            item(key = "dim-level") {
                DimLevelCard(
                    opacity = state.opacity,
                    engine = state.engine,
                    onOpacityChange = onOpacityChange,
                )
            }

            item(key = "touch-note") {
                TouchPassthroughNote()
            }

            item(key = "apps-header") {
                AppsHeader(
                    selectedCount = state.selectedPackages.size,
                    query = query,
                    onQueryChange = { query = it },
                    onClearSelection = onClearSelection,
                )
            }

            if (state.appsLoading) {
                item(key = "loading") { LoadingRow() }
            } else {
                if (state.selectedPackages.isEmpty() && filteredApps.isNotEmpty()) {
                    item(key = "empty-selection") { EmptySelectionHint() }
                }

                items(
                    items = filteredApps,
                    key = { it.packageName },
                ) { app ->
                    AppRow(
                        app = app,
                        selected = app.packageName in state.selectedPackages,
                        onToggle = { onToggleApp(app.packageName) },
                    )
                }

                if (filteredApps.isEmpty()) {
                    item(key = "no-results") { NoResults(query = query) }
                }
            }
        }
    }

    if (state.showAccessibilityDialog) {
        AccessibilityDialog(
            onConfirm = onRequestAccessibility,
            onDismiss = onDismissAccessibilityDialog,
        )
    }
}

@Composable
private fun Header(
    state: MainUiState,
    onToggleEnabled: (Boolean) -> Unit,
) {
    val ready = state.enabled &&
        state.selectedPackages.isNotEmpty() &&
        when (state.engine) {
            OverlayEngine.ACCESSIBILITY -> state.accessibilityEnabled
            OverlayEngine.COMPATIBILITY -> state.overlayGranted && state.usageAccessGranted
        }
    val dotColor = when {
        ready -> ReadyGreen
        state.enabled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_moon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(27.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = "App Dimmer",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = statusText(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Switch(
            checked = state.enabled,
            onCheckedChange = onToggleEnabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MutedText,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

private fun statusText(state: MainUiState): String = when {
    !state.enabled -> "Off · turn on to start dimming"
    state.engine == OverlayEngine.COMPATIBILITY && !state.overlayGranted ->
        "Display permission needed"
    state.engine == OverlayEngine.COMPATIBILITY && !state.usageAccessGranted ->
        "Usage access needed"
    state.engine == OverlayEngine.ACCESSIBILITY && !state.accessibilityEnabled ->
        "Accessibility access needed"
    state.selectedPackages.isEmpty() -> "On · choose apps to dim"
    else -> {
        val count = state.selectedPackages.size
        "On · dimming $count ${if (count == 1) "app" else "apps"}"
    }
}

@Composable
private fun SetupCard(
    state: MainUiState,
    onRequestOverlayPermission: () -> Unit,
    onRequestUsageAccess: () -> Unit,
    onRequestAccessibility: () -> Unit,
) {
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Finish setup",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = when (state.engine) {
                        OverlayEngine.ACCESSIBILITY ->
                            "Accessibility access is needed before dimming can work."
                        OverlayEngine.COMPATIBILITY ->
                            "Two permissions are needed for the compatibility overlay."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        when (state.engine) {
            OverlayEngine.ACCESSIBILITY -> {
                PermissionRow(
                    title = "Accessibility access",
                    description = "Detects the app on screen. Package names only.",
                    granted = state.accessibilityEnabled,
                    onGrant = onRequestAccessibility,
                )
            }

            OverlayEngine.COMPATIBILITY -> {
                PermissionRow(
                    title = "Display over other apps",
                    description = "Draws the compatibility overlay on top of apps.",
                    granted = state.overlayGranted,
                    onGrant = onRequestOverlayPermission,
                )
                Spacer(Modifier.height(10.dp))
                PermissionRow(
                    title = "Usage access",
                    description = "Detects which app is in the foreground.",
                    granted = state.usageAccessGranted,
                    onGrant = onRequestUsageAccess,
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (granted) Icons.Filled.Check else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (granted) ReadyGreen else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
        }
        Spacer(Modifier.width(8.dp))

        if (granted) {
            Text(
                text = "On",
                style = MaterialTheme.typography.labelMedium,
                color = ReadyGreen,
            )
        } else {
            FilledTonalButton(
                onClick = onGrant,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier.height(34.dp),
            ) {
                Text("Grant", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun OverlayModeCard(
    engine: OverlayEngine,
    onEngineChange: (OverlayEngine) -> Unit,
) {
    SurfaceCard {
        Text(
            text = "Overlay mode",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Choose how the dim layer is drawn.",
            style = MaterialTheme.typography.bodySmall,
            color = MutedText,
        )

        Spacer(Modifier.height(12.dp))

        ModeOption(
            selected = engine == OverlayEngine.ACCESSIBILITY,
            title = "Full black",
            description = "Accessibility overlay · up to 100%",
            onClick = { onEngineChange(OverlayEngine.ACCESSIBILITY) },
        )

        Spacer(Modifier.height(8.dp))

        ModeOption(
            selected = engine == OverlayEngine.COMPATIBILITY,
            title = "Compatibility",
            description = "Foreground service + Usage access · max 80%",
            onClick = { onEngineChange(OverlayEngine.COMPATIBILITY) },
        )

        if (engine == OverlayEngine.COMPATIBILITY) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Independent of Accessibility. Runs a low-priority " +
                    "notification and is capped at 80% by Android.",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
        }
    }
}

@Composable
private fun ModeOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.outline,
            ),
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
        }
    }
}

@Composable
private fun DimLevelCard(
    opacity: Int,
    engine: OverlayEngine,
    onOpacityChange: (Int) -> Unit,
) {
    val isCompatibility = engine == OverlayEngine.COMPATIBILITY
    val previewOpacity = if (isCompatibility) {
        (opacity * 0.8f).roundToInt()
    } else {
        opacity
    }

    SurfaceCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Dim level",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "How dark the overlay should be.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText,
                )
            }
            Text(
                text = "$opacity%",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(8.dp))

        Slider(
            value = opacity.toFloat(),
            onValueChange = { onOpacityChange(it.roundToInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )

        Row(Modifier.fillMaxWidth()) {
            Text(
                text = "0% · unchanged",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (isCompatibility) "100% · 80% max" else "100% · black",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
        }

        Spacer(Modifier.height(14.dp))

        DimPreview(opacity = previewOpacity)

        if (isCompatibility) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Compatibility mode is capped at 80% black by Android.",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
        }
    }
}

@Composable
private fun DimPreview(opacity: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(RoundedCornerShape(16.dp)),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF24405F),
                            Color(0xFF3E6FA8),
                            Color(0xFF7FB6E8),
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.88f)),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Box(
                        Modifier
                            .width(96.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.88f)),
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .width(64.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.55f)),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(22.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color.White.copy(alpha = 0.32f)),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = opacity / 100f)),
        )
    }
}

@Composable
private fun TouchPassthroughNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            tint = MutedText,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "The overlay ignores touch, so the app underneath stays fully usable.",
            style = MaterialTheme.typography.bodySmall,
            color = MutedText,
        )
    }
}

@Composable
private fun AppsHeader(
    selectedCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    onClearSelection: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Apps",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (selectedCount > 0) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "$selectedCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            if (selectedCount > 0) {
                TextButton(
                    onClick = onClearSelection,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) {
                    Text("Clear", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Text(
            text = "Only the apps you select will be dimmed.",
            style = MaterialTheme.typography.bodySmall,
            color = MutedText,
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = "Search apps",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedText,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MutedText,
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Clear search",
                            tint = MutedText,
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            bitmap = app.icon,
            contentDescription = null,
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp)),
        )

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(8.dp))

        Checkbox(
            checked = selected,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary,
                checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                uncheckedColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

@Composable
private fun EmptySelectionHint() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Pick one or more apps below. The overlay only appears inside those apps.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun LoadingRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 44.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun NoResults(query: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No apps found",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (query.isBlank()) {
                "No launchable apps were found on this device."
            } else {
                "Nothing matches \"$query\"."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MutedText,
        )
    }
}

@Composable
private fun AccessibilityDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        icon = {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = "Enable accessibility access",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Text(
                text = "Android does not let apps see which app is in the foreground " +
                    "without Accessibility. App Dimmer reads only the app's package " +
                    "name to decide when to dim.\n\n" +
                    "It cannot read screen content or control your device. Enable " +
                    "\"App Dimmer\" under Settings → Accessibility.",
                style = MaterialTheme.typography.bodySmall,
                color = MutedText,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Open settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not now")
            }
        },
    )
}

@Composable
private fun SurfaceCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            content = content,
        )
    }
}
