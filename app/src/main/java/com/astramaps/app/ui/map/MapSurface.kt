package com.astramaps.app.ui.map

import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.astramaps.app.data.map.LocalPack
import com.astramaps.app.map.MapStyleFactory
import com.astramaps.app.ui.theme.Palette
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * The map surface.
 *
 * A MapLibre `MapView` wrapped for Compose. The style is generated from the
 * gated palette and pointed at the pack on disk, so the only thing the renderer
 * ever reads is `pmtiles://file://…` — there is no remote style, no raster
 * fallback and no glyph fetch.
 *
 * The camera starts at the centre recorded in the archive header, which is why
 * a freshly copied pack needs no separate configuration to be usable.
 */
@Composable
fun MapSurface(
    pack: LocalPack,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val styleJson = remember(pack.archiveUrl, darkTheme) {
        MapStyleFactory.build(Palette.load(context).themeFor(darkTheme), pack.archiveUrl)
    }
    val cameraPosition = remember(pack) {
        CameraPosition.Builder()
            .target(LatLng(pack.header.centerLat, pack.header.centerLon))
            .zoom(pack.header.centerZoom.toDouble())
            .build()
    }

    /**
     * The pack's bounds, used to frame whatever was installed.
     *
     * A pack can be a city or a continent; its declared centre zoom is a hint,
     * not a fit. Framing the bounds makes the fixture fill the screen and keeps
     * a large pack from starting deep inside one district.
     */
    val packBounds = remember(pack) {
        val bounds = pack.header.bounds
        if (bounds.maxLon - bounds.minLon > 0.0 && bounds.maxLat - bounds.minLat > 0.0) {
            LatLngBounds.Builder()
                .include(LatLng(bounds.minLat, bounds.minLon))
                .include(LatLng(bounds.maxLat, bounds.maxLon))
                .build()
        } else {
            null
        }
    }

    val mapView = remember(pack) {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                // The attribution is rendered by this composable instead, so it
                // stays visible on every theme and cannot be collapsed away.
                map.uiSettings.isAttributionEnabled = false
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isCompassEnabled = true
            }
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        // A composable that is added to an already-started host never sees the
        // ON_START/ON_RESUME events it missed, and a MapView that is never
        // started never delivers `getMapAsync` — the map simply stays blank with
        // no error. Catch the lifecycle up with where it already is.
        val state = lifecycleOwner.lifecycle.currentState
        if (state.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (state.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            (mapView.parent as? ViewGroup)?.removeView(mapView)
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Restyling on every recomposition would reload the style and flash the
    // map, so the applied style is tracked explicitly.
    var appliedStyle by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier.semantics { contentDescription = "Map surface" }) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                val style = styleJson
                if (appliedStyle != style) {
                    appliedStyle = style
                    view.getMapAsync { map ->
                        map.setStyle(Style.Builder().fromJson(style)) {
                            if (packBounds != null) {
                                map.moveCamera(CameraUpdateFactory.newLatLngBounds(packBounds, 32))
                            } else {
                                map.cameraPosition = cameraPosition
                            }
                        }
                    }
                }
            },
        )

        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
        ) {
            Text(
                text = "© OpenStreetMap contributors",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

