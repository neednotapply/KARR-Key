package com.karr.key.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.karr.key.R
import com.karr.key.core.AuthorizationDecision
import com.karr.key.core.FeatureDefinition
import com.karr.key.core.RolePolicy
import com.karr.key.core.Session
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel

internal sealed interface FeatureAvailability {
    val label: String
    val description: String
    val iconRes: Int

    data object Available : FeatureAvailability {
        override val label = "Ready"
        override val description = "Available"
        override val iconRes = R.drawable.ic_status_check
    }

    data class DeviceRequired(override val description: String) : FeatureAvailability {
        override val label = "Device auth"
        override val iconRes = R.drawable.ic_nav_bluetooth
    }

    data class Locked(override val description: String) : FeatureAvailability {
        override val label = "Locked"
        override val iconRes = R.drawable.ic_status_lock
    }

    data class ModelLimited(override val description: String) : FeatureAvailability {
        override val label = "Limited"
        override val iconRes = R.drawable.ic_status_lock
    }

    data class ModelUnsupported(override val description: String) : FeatureAvailability {
        override val label = "Not supported"
        override val iconRes = R.drawable.ic_status_lock
    }

    data class ModelUnknown(override val description: String) : FeatureAvailability {
        override val label = "Support unknown"
        override val iconRes = R.drawable.ic_nav_bluetooth
    }

    data class HardwareUnsupported(override val description: String) : FeatureAvailability {
        override val label = "Hardware"
        override val iconRes = R.drawable.ic_status_lock
    }

    data class HardwareUnknown(override val description: String) : FeatureAvailability {
        override val label = "Hardware unknown"
        override val iconRes = R.drawable.ic_nav_bluetooth
    }

    data class ModeRestricted(override val description: String) : FeatureAvailability {
        override val label = "Unit mode"
        override val iconRes = R.drawable.ic_status_lock
    }

    data class ModeUnknown(override val description: String) : FeatureAvailability {
        override val label = "Mode unknown"
        override val iconRes = R.drawable.ic_nav_bluetooth
    }
}

internal fun featureAvailability(
    session: Session,
    feature: FeatureDefinition,
    deviceAuthenticated: Boolean,
    unitModel: KarrUnitModel? = null,
    unitMode: KarrDeviceMode? = null,
): FeatureAvailability {
    return when (val decision = RolePolicy().authorize(session, feature, deviceAuthenticated)) {
        AuthorizationDecision.Allowed -> {
            if (!deviceAuthenticated) {
                FeatureAvailability.Available
            } else {
                when (
                    val compatibility = featureModelCompatibility(feature, unitModel, unitMode)
                ) {
                    FeatureModelCompatibility.Supported -> FeatureAvailability.Available
                    is FeatureModelCompatibility.Limited ->
                        FeatureAvailability.ModelLimited(compatibility.description)
                    is FeatureModelCompatibility.Unsupported ->
                        FeatureAvailability.ModelUnsupported(compatibility.description)
                    is FeatureModelCompatibility.Unknown ->
                        FeatureAvailability.ModelUnknown(compatibility.description)
                }
            }
        }
        is AuthorizationDecision.Denied -> {
            if (feature.requiredRole in session.roles &&
                feature.safetySensitive &&
                !deviceAuthenticated
            ) {
                FeatureAvailability.DeviceRequired(decision.reason)
            } else {
                FeatureAvailability.Locked(decision.reason)
            }
        }
    }
}

@Composable
internal fun ResponsiveContent(
    modifier: Modifier = Modifier,
    contentMaxWidth: androidx.compose.ui.unit.Dp = 840.dp,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val horizontalPadding = if (this.maxWidth >= 600.dp) 24.dp else 16.dp
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            content(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = contentMaxWidth)
                    .padding(horizontal = horizontalPadding),
            )
        }
    }
}

@Composable
internal fun SectionHeader(
    title: String,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { heading() },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        supportingText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun StatusPill(
    availability: FeatureAvailability,
    modifier: Modifier = Modifier,
) {
    val container: Color
    val content: Color
    when (availability) {
        FeatureAvailability.Available -> {
            container = MaterialTheme.colorScheme.primaryContainer
            content = MaterialTheme.colorScheme.onPrimaryContainer
        }
        is FeatureAvailability.DeviceRequired -> {
            container = MaterialTheme.colorScheme.tertiaryContainer
            content = MaterialTheme.colorScheme.onTertiaryContainer
        }
        is FeatureAvailability.Locked -> {
            container = MaterialTheme.colorScheme.surfaceVariant
            content = MaterialTheme.colorScheme.onSurfaceVariant
        }
        is FeatureAvailability.ModelLimited,
        is FeatureAvailability.ModelUnknown,
        is FeatureAvailability.HardwareUnknown,
        is FeatureAvailability.ModeRestricted,
        is FeatureAvailability.ModeUnknown,
        -> {
            container = MaterialTheme.colorScheme.tertiaryContainer
            content = MaterialTheme.colorScheme.onTertiaryContainer
        }
        is FeatureAvailability.ModelUnsupported,
        is FeatureAvailability.HardwareUnsupported,
        -> {
            container = MaterialTheme.colorScheme.errorContainer
            content = MaterialTheme.colorScheme.onErrorContainer
        }
    }
    Surface(
        modifier = modifier.semantics {
            stateDescription = availability.description
        },
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusIcon(
                iconRes = availability.iconRes,
                contentDescription = null,
            )
            Text(
                text = availability.label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun StatusIcon(
    @DrawableRes iconRes: Int,
    contentDescription: String?,
) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = contentDescription,
        modifier = Modifier.size(16.dp),
    )
}

@Composable
internal fun TechnicalDetails(
    title: String = "Technical details",
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more),
                    contentDescription = if (expanded) {
                        "Collapse $title"
                    } else {
                        "Expand $title"
                    },
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
internal fun CodeSurface(
    text: String,
    modifier: Modifier = Modifier,
) {
    SelectionContainer {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                softWrap = true,
            )
        }
    }
}
