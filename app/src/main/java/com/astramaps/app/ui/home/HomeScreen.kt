package com.astramaps.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.astramaps.app.BuildConfig
import com.astramaps.app.data.map.LocalPack
import com.astramaps.app.ui.map.MapSurface

/**
 * Home screen.
 *
 * When a map pack is present this is the map surface: that is the whole point
 * of the app, and it is what Phase 3 puts in place. With no pack installed it
 * falls back to the build/runtime panel, which is also the state the offline
 * acceptance harness and the emulator start from.
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    installedPackIds: List<String> = emptyList(),
    mapPack: LocalPack? = null,
    darkTheme: Boolean = false,
) {
    if (mapPack != null) {
        MapSurface(
            pack = mapPack,
            darkTheme = darkTheme,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    Scaffold(modifier = modifier) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "Astra Maps",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item {
                Text(
                    text = "Offline-first cycling navigation",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Build", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "User-Agent: ${BuildConfig.TILE_USER_AGENT}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Installed packs", style = MaterialTheme.typography.labelMedium)
                        if (installedPackIds.isEmpty()) {
                            Text(
                                text = "None yet — regional packs arrive in Phase 6.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            items(installedPackIds) { packId ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = packId,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
