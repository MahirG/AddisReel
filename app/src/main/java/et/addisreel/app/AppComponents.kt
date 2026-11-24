package et.addisreel.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

/** Shared, brand-aware primitives used by every Android top-level destination. */
@Composable
internal fun AddisScreenTitle(title: String, subtitle: String? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = AddisSpacing.standard, vertical = AddisSpacing.compact
        ),
        verticalArrangement = Arrangement.spacedBy(AddisSpacing.micro)
    ) {
        Text(title, style = AddisScript.style(title, MaterialTheme.typography.headlineLarge),
            color = AddisColors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
            Text(subtitle, style = AddisScript.style(subtitle, MaterialTheme.typography.bodyMedium),
                color = AddisColors.textSecondary)
        }
    }
}

@Composable
internal fun AddisSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = AddisScript.style(title, MaterialTheme.typography.titleLarge),
        color = AddisColors.textPrimary,
        modifier = modifier,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

internal enum class AddisStatus { Info, Success, Warning, Error }

@Composable
internal fun AddisStatusMessage(
    message: String,
    status: AddisStatus = AddisStatus.Info,
    modifier: Modifier = Modifier
) {
    val tone = when (status) {
        AddisStatus.Info -> AddisColors.info
        AddisStatus.Success -> AddisColors.success
        AddisStatus.Warning -> AddisColors.warning
        AddisStatus.Error -> AddisColors.error
    }
    val symbol = when (status) {
        AddisStatus.Info -> Icons.Default.Info
        AddisStatus.Success -> Icons.Default.CheckCircle
        AddisStatus.Warning -> Icons.Default.WarningAmber
        AddisStatus.Error -> Icons.Default.ErrorOutline
    }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = AddisColors.elevated
    ) {
        Row(
            Modifier.padding(AddisSpacing.standard),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AddisSpacing.compact)
        ) {
            Icon(symbol, contentDescription = null, tint = tone, modifier = Modifier.size(AddisIcons.standard))
            Text(message, modifier = Modifier.weight(1f),
                style = AddisScript.style(message, MaterialTheme.typography.bodyMedium),
                color = AddisColors.textPrimary)
        }
    }
}

/** One touch-target and radius policy for all selection chips. */
@Composable
internal fun AddisChoiceChip(
    selected: Boolean, onClick: () -> Unit,
    label: @Composable () -> Unit
) {
    FilterChip(
        selected = selected, onClick = onClick,
        modifier = Modifier.heightIn(min = AddisSpacing.touch),
        shape = MaterialTheme.shapes.small,
        label = label
    )
}

/** Theme control is visible and reachable at all supported system font scales. */
@Composable
internal fun AddisAppearancePicker() {
    val choice = LocalAddisThemeSelection.current
    Column(
        verticalArrangement = Arrangement.spacedBy(AddisSpacing.small),
        modifier = Modifier.fillMaxWidth()
    ) {
        AddisSectionTitle(stringResource(R.string.appearance))
        Text(stringResource(R.string.appearance_description),
            style = MaterialTheme.typography.bodyMedium, color = AddisColors.textSecondary)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(AddisSpacing.small)
        ) {
            listOf("System", "Light", "Dark").forEach { mode ->
                FilterChip(
                    selected = mode == choice.value,
                    onClick = { choice.change(mode) },
                    modifier = Modifier.heightIn(min = AddisSpacing.touch),
                    label = {
                        Text(when (mode) {
                            "System" -> stringResource(R.string.theme_system)
                            "Light" -> stringResource(R.string.theme_light)
                            else -> stringResource(R.string.theme_dark)
                        }, style = MaterialTheme.typography.labelLarge)
                    }
                )
            }
        }
    }
}
