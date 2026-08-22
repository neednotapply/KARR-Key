package com.karr.key.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.karr.key.data.LocalVehicleIdentityStore
import com.karr.key.data.VehicleIdentity
import com.karr.key.data.decodeVinWithVpic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun vinFromLines(lines: List<String>): String? = lines
    .firstOrNull { line -> line.startsWith("VIN: ") }
    ?.removePrefix("VIN: ")
    ?.takeIf { vin -> vin.length == 17 }

@Composable
internal fun VinIdentityContent(
    vin: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember(context) { LocalVehicleIdentityStore(context) }
    val scope = rememberCoroutineScope()
    var identity by remember(vin) { mutableStateOf<VehicleIdentity?>(null) }
    var loading by remember(vin) { mutableStateOf(false) }
    var error by remember(vin) { mutableStateOf<String?>(null) }

    LaunchedEffect(vin) {
        repeat(60) {
            identity = withContext(Dispatchers.IO) { store.get(vin) }
            if (identity != null) return@LaunchedEffect
            delay(500)
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        identity?.let { decoded ->
            Text(
                decoded.title.ifBlank { "Vehicle decoded" },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("vin-identity:$vin"),
            )
            decoded.details?.let { details ->
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } ?: TextButton(
            onClick = {
                if (!loading) {
                    loading = true
                    error = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { decodeVinWithVpic(vin) } }
                            .onSuccess { decoded ->
                                withContext(Dispatchers.IO) { store.put(decoded) }
                                identity = decoded
                            }
                            .onFailure { failure -> error = failure.message ?: "VIN decode failed." }
                        loading = false
                    }
                }
            },
            enabled = !loading,
            modifier = Modifier.testTag("decode-vin:$vin"),
        ) {
            Text(if (loading) "Decoding…" else "Decode VIN with NHTSA")
        }
        if (identity == null) {
            Text(
                "Automatic decoding was unavailable. Retry sends the VIN to the public NHTSA vPIC service.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        error?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
