package com.noworrieshq.restamp.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.noworrieshq.restamp.core.model.GeoLocation
import com.noworrieshq.restamp.media.PlaceSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/**
 * Picks a location: search for a city or place, then tap the map to place the pin exactly.
 * Uses OpenStreetMap tiles and search, which are free and need no API key.
 */
@Composable
fun LocationPickerScreen(initial: GeoLocation?, onCancel: () -> Unit, onPicked: (GeoLocation) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val search = remember { PlaceSearch(userAgent(context)) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<PlaceSearch.Place>()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf(initial?.let { GeoPoint(it.latitude, it.longitude) }) }
    val map = remember { createMap(context) }
    val marker = remember { Marker(map).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "Picked location" } }

    fun place(point: GeoPoint, zoom: Double? = null) {
        picked = point
        marker.position = point
        if (marker !in map.overlays) map.overlays.add(marker)
        zoom?.let { map.controller.setZoom(it) }
        map.controller.animateTo(point)
        map.invalidate()
    }

    DisposableEffect(map) {
        map.overlays.add(
            MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { place(p); return true }
                override fun longPressHelper(p: GeoPoint): Boolean { place(p); return true }
            }),
        )
        map.overlays.add(CopyrightOverlay(context))
        val start = picked
        if (start != null) {
            map.controller.setZoom(14.0)
            map.controller.setCenter(start)
            marker.position = start
            map.overlays.add(marker)
        } else {
            map.controller.setZoom(3.0)
            map.controller.setCenter(GeoPoint(20.0, 0.0))
        }
        map.onResume()
        onDispose {
            map.onPause()
            map.onDetach()
        }
    }

    fun runSearch() {
        if (searching) return
        searching = true
        searchError = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { search.search(query) } }
                .onSuccess { results = it; if (it.isEmpty()) searchError = "No places found" }
                .onFailure { searchError = "Search needs an internet connection (${it.message})" }
            searching = false
        }
    }

    BackHandler(onBack = onCancel)

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search for a city or place") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                    modifier = Modifier.weight(1f),
                )
                TextButton(enabled = !searching && query.isNotBlank(), onClick = ::runSearch) { Text(if (searching) "…" else "Search") }
            }
            searchError?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall) }
            if (results.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    items(results) { r ->
                        Text(
                            r.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().clickable {
                                results = emptyList()
                                place(GeoPoint(r.latitude, r.longitude), zoom = 13.0)
                            }.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                        HorizontalDivider()
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
            }
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        picked?.let { "Pin: %.5f, %.5f. Tap the map to move it.".format(it.latitude, it.longitude) }
                            ?: "Search, or tap the map to drop a pin.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onCancel) { Text("Cancel") }
                        Button(
                            enabled = picked != null,
                            onClick = { picked?.let { onPicked(GeoLocation(it.latitude, it.longitude)) } },
                        ) { Text("Use this location") }
                    }
                }
            }
        }
    }
}

private fun userAgent(context: Context): String {
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "dev"
    return "ReStamp/$version (Android; ${context.packageName})"
}

private fun createMap(context: Context): MapView {
    Configuration.getInstance().apply {
        load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        // OpenStreetMap's tile policy requires an identifying User-Agent.
        userAgentValue = userAgent(context)
        // Keep the tile cache in app-private storage, so no storage permission is needed and
        // it goes away with the app.
        osmdroidBasePath = java.io.File(context.cacheDir, "osmdroid")
        osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid/tiles")
    }
    return MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        isTilesScaledToDpi = true
        minZoomLevel = 2.0
    }
}
