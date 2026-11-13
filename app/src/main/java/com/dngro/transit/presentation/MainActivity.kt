package com.dngro.transit.presentation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.TitleCard
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import com.dngro.transit.data.AppPrefs
import com.dngro.transit.data.RADIUS_OPTIONS_MILES
import com.dngro.transit.data.radiusLabel
import com.dngro.transit.presentation.theme.MtaPastelBlue
import com.dngro.transit.presentation.theme.MtaPastelGold
import com.dngro.transit.presentation.theme.MtaPastelGreen
import com.dngro.transit.presentation.theme.MtaPastelPurple
import com.dngro.transit.presentation.theme.TransitTheme
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class MainActivity : ComponentActivity() {
    private val viewModel: BusViewModel by viewModels()

    /** True while the watch is in its dimmed, low-power "always on" display state. */
    private var isAmbient by mutableStateOf(false)

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            isAmbient = true
        }

        override fun onExitAmbient() {
            isAmbient = false
        }

        override fun onUpdateAmbient() = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)

        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))
        applyApiKeyFromIntent(intent)

        setContent {
            TransitTheme {
                val context = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current
                val ambient = isAmbient
                var hasPermission by remember { mutableStateOf(context.hasLocationPermission()) }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { permissions ->
                    hasPermission = permissions.values.any { it }
                }

                // Ask straight away on first launch, but only if we don't already have it.
                LaunchedEffect(Unit) {
                    if (!hasPermission) permissionLauncher.launch(LOCATION_PERMISSIONS)
                }

                // Pick up a permission granted in system Settings when the user returns.
                LaunchedEffect(lifecycleOwner) {
                    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        hasPermission = context.hasLocationPermission()
                    }
                }

                if (hasPermission) {
                    // Load when the app comes to the foreground (if stale), then keep fresh
                    // while the screen is on. Nothing runs while backgrounded or in ambient
                    // mode; leaving ambient mode restarts this and refreshes if needed.
                    LaunchedEffect(lifecycleOwner, ambient) {
                        if (!ambient) {
                            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.refreshIfStale()
                                while (true) {
                                    delay(AUTO_REFRESH_CHECK_MS)
                                    viewModel.refreshIfStale(retryErrors = false)
                                }
                            }
                        }
                    }
                    BusApp(viewModel, ambient)
                } else {
                    PermissionScreen(onAllow = { permissionLauncher.launch(LOCATION_PERMISSIONS) })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyApiKeyFromIntent(intent)
    }

    /**
     * Lets a key be pushed over adb instead of typed on the watch:
     * `adb shell am start -n com.dngro.transit/.presentation.MainActivity --es mta_api_key YOUR_KEY`
     * Any app on the watch could send this, but the worst it can do is swap the key the app uses.
     */
    private fun applyApiKeyFromIntent(intent: Intent?) {
        val key = intent?.getStringExtra(EXTRA_API_KEY) ?: return
        intent.removeExtra(EXTRA_API_KEY) // don't re-apply when the activity is recreated
        viewModel.saveApiKey(key)
    }

    companion object {
        const val EXTRA_API_KEY = "mta_api_key"
    }
}

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

/** While the screen is on, check this often whether the results are old enough to refresh. */
private const val AUTO_REFRESH_CHECK_MS = 30_000L

/** How often "N min away" labels are recomputed between network refreshes. */
private const val CLOCK_TICK_MS = 15_000L

/** Ambient mode redraws about once a minute, so recompute no more often than that. */
private const val AMBIENT_TICK_MS = 60_000L

private val clockFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

private fun Context.hasLocationPermission(): Boolean =
    LOCATION_PERMISSIONS.any {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

@Composable
fun BusApp(viewModel: BusViewModel, ambient: Boolean = false) {
    val listState = rememberScalingLazyListState()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val radiusMiles by viewModel.radiusMiles.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(Screen.List) }

    // Recompute "N min away" between network refreshes so labels never go stale.
    val now by produceState(initialValue = Instant.now(), ambient) {
        // The producer restarts when ambient changes but keeps the old value: refresh it at once.
        value = Instant.now()
        while (true) {
            delay(if (ambient) AMBIENT_TICK_MS else CLOCK_TICK_MS)
            value = Instant.now()
        }
    }

    if (ambient) {
        AmbientScreen(state = uiState, now = now)
        return
    }

    when (screen) {
        Screen.ApiKey -> {
            BackHandler { screen = Screen.List }
            ApiKeyScreen(
                onSave = { key ->
                    viewModel.saveApiKey(key)
                    screen = Screen.List
                },
                onCancel = { screen = Screen.List },
            )
            return
        }
        Screen.Settings -> {
            BackHandler { screen = Screen.List }
            SettingsScreen(
                radiusMiles = radiusMiles,
                onRadiusSelected = { viewModel.setRadiusMiles(it) },
                onEditApiKey = { screen = Screen.ApiKey },
                onDone = { screen = Screen.List },
            )
            return
        }
        Screen.List -> Unit
    }

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentAlignment = Alignment.Center,
        ) {
            when (val state = uiState) {
                is UiState.Loading -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(
                        indicatorColor = MtaPastelBlue,
                        trackColor = MtaPastelBlue.copy(alpha = 0.2f),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Locating nearby buses...",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    )
                }
                is UiState.Error -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(text = state.message, textAlign = TextAlign.Center, style = MaterialTheme.typography.body2)
                    Spacer(modifier = Modifier.height(12.dp))
                    if (state.needsApiKey) {
                        Chip(
                            onClick = { screen = Screen.ApiKey },
                            label = { Text("Enter API key") },
                            colors = ChipDefaults.primaryChipColors(
                                backgroundColor = MtaPastelBlue,
                                contentColor = Color.Black,
                            ),
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CompactButton(
                                onClick = { viewModel.refresh() },
                                colors = ButtonDefaults.buttonColors(backgroundColor = MtaPastelBlue),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.Black)
                            }
                            CompactButton(onClick = { screen = Screen.Settings }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        }
                    }
                }
                is UiState.Success -> {
                    ScalingLazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // Crown / rotating bezel scrolls the list (this is also the default).
                        rotaryScrollableBehavior = RotaryScrollableDefaults.behavior(listState),
                    ) {
                        item {
                            ListHeader {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MtaPastelGreen),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "NYC Buses On A Glance",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colors.onBackground,
                                    )
                                }
                            }
                        }
                        if (state.stale || state.fromCache) {
                            item {
                                Text(
                                    text = if (state.stale) {
                                        "Couldn't update · showing last results"
                                    } else {
                                        "Updating…"
                                    },
                                    style = MaterialTheme.typography.caption2,
                                    color = MtaPastelGold,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        items(state.routes) { route ->
                            BusRouteGroup(
                                route = route,
                                expandedRouteIds = state.expandedRouteIds,
                                now = now,
                                onRouteToggle = { viewModel.toggleRouteExpansion(route.routeShortName) },
                                onFavoriteToggle = { direction -> viewModel.toggleFavorite(route, direction) },
                            )
                        }
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CompactButton(
                                        onClick = { viewModel.refresh() },
                                        enabled = !isRefreshing,
                                        colors = ButtonDefaults.buttonColors(backgroundColor = MtaPastelBlue),
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.Black)
                                    }
                                    CompactButton(onClick = { screen = Screen.Settings }) {
                                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The low-power "always on" view: black background, plain text, no colour fills, and nothing
 * that animates. It lists the next bus for the first few directions.
 */
@Composable
private fun AmbientScreen(state: UiState, now: Instant) {
    Scaffold(timeText = { TimeText() }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (state is UiState.Success) {
                    state.routes
                        .flatMap { route -> route.directions.map { route to it } }
                        .take(AMBIENT_ROWS)
                        .forEach { (route, direction) ->
                            val next = direction.upcoming(now).firstNotNullOfOrNull { it.expected }
                            val minutes = next?.let { minutesUntil(it, now) }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = route.routeShortName,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = direction.destinationName,
                                    color = Color.LightGray,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = when {
                                        minutes == null -> "–"
                                        minutes <= 0L -> "Now"
                                        else -> "$minutes min"
                                    },
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                } else {
                    Text(text = "NYC Buses", color = Color.White, fontSize = 14.sp)
                    Text(text = "Raise your wrist to refresh", color = Color.LightGray, fontSize = 11.sp)
                }
            }
        }
    }
}

private const val AMBIENT_ROWS = 4

@Composable
fun BusRouteGroup(
    route: BusRouteUiModel,
    expandedRouteIds: Set<String>,
    now: Instant,
    onRouteToggle: () -> Unit,
    onFavoriteToggle: (BusDirectionUiModel) -> Unit,
) {
    val isRouteExpanded = expandedRouteIds.contains(route.routeShortName)
    val badgeColor = if (route.routeShortName.contains("SBS", ignoreCase = true)) {
        MtaPastelPurple
    } else {
        MtaPastelBlue
    }

    TitleCard(
        onClick = onRouteToggle,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // MTA Route Badge Chip
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColor)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = route.routeShortName,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.Black,
                        fontSize = 14.sp,
                    )
                }
                if (route.alerts.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "⚠", color = MtaPastelGold, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = if (isRouteExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (route.isPinned) {
                    Modifier.border(1.5.dp, MtaPastelGold, RoundedCornerShape(24.dp))
                } else {
                    Modifier
                },
            ),
    ) {
        if (isRouteExpanded) {
            Column {
                route.alerts.forEach { alert ->
                    Text(
                        text = "⚠ $alert",
                        style = MaterialTheme.typography.caption2,
                        color = MtaPastelGold,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                route.directions.forEach { direction ->
                    DirectionItem(
                        routeName = route.routeShortName,
                        direction = direction,
                        now = now,
                        onFavoriteToggle = { onFavoriteToggle(direction) },
                    )
                    Spacer(
                        modifier = Modifier
                            .height(1.dp)
                            .fillMaxWidth()
                            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.1f)),
                    )
                }
            }
        }
    }
}

@Composable
fun DirectionItem(
    routeName: String,
    direction: BusDirectionUiModel,
    now: Instant,
    onFavoriteToggle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Star this route + direction + stop: it then shows live on the tile and watch face.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onFavoriteToggle() }
                    .padding(end = 6.dp, top = 2.dp, bottom = 2.dp),
            ) {
                Icon(
                    imageVector = if (direction.isPinned) Icons.Default.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (direction.isPinned) {
                        "Unstar $routeName to ${direction.destinationName}"
                    } else {
                        "Star $routeName to ${direction.destinationName}"
                    },
                    tint = if (direction.isPinned) MtaPastelGold else MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = "to ${direction.destinationName}",
                style = MaterialTheme.typography.caption1,
                fontWeight = FontWeight.Bold,
                color = MtaPastelGold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (direction.stopDistanceText.isNotEmpty()) {
                Text(
                    text = direction.stopDistanceText,
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
            }
        }

        Text(
            text = "at ${direction.stopName}",
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
        )

        val upcoming = direction.upcoming(now)
        if (upcoming.isEmpty()) {
            Text(
                text = "No scheduled buses",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
            )
        } else {
            upcoming.forEach { arrival ->
                val minutes = arrival.expected?.let { minutesUntil(it, now) }
                val minutesText = when {
                    minutes == null -> ""
                    minutes <= 0L -> "Arriving"
                    else -> "$minutes min away"
                }
                val clockTime = arrival.expected?.let { clockFormatter.format(it) }.orEmpty()
                val isArriving = minutes != null && minutes <= 1L
                val timeColor = if (isArriving) MtaPastelGreen else MtaPastelGold

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (minutesText.isEmpty()) arrival.distanceText else "${arrival.distanceText} ($minutesText)",
                        style = MaterialTheme.typography.body2,
                        color = timeColor,
                        fontWeight = if (isArriving) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(
                        text = clockTime,
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionScreen(onAllow: () -> Unit) {
    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Location needed",
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Allow location to find bus stops near you. If no prompt appears, enable it in Settings.",
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Chip(
                onClick = onAllow,
                label = { Text("Allow") },
                colors = ChipDefaults.primaryChipColors(
                    backgroundColor = MtaPastelBlue,
                    contentColor = Color.Black,
                ),
            )
        }
    }
}

/**
 * Type the MTA key on the watch. Typing 30-plus characters is slow, so the README also shows how
 * to push it over adb instead.
 */
@Composable
private fun ApiKeyScreen(onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }

    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "MTA API key",
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colors.surface)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (text.isEmpty()) {
                    Text(
                        text = "Tap to type your key",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CompactButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                }
                CompactButton(
                    onClick = { onSave(text) },
                    enabled = text.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(backgroundColor = MtaPastelBlue),
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Save key", tint = Color.Black)
                }
            }
        }
    }
}

private enum class Screen { List, Settings, ApiKey }

/** Search radius and API key. Radius changes apply (and reload) as soon as one is tapped. */
@Composable
private fun SettingsScreen(
    radiusMiles: Double,
    onRadiusSelected: (Double) -> Unit,
    onEditApiKey: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    val keyStatus = if (AppPrefs.hasUserApiKey(context)) "Custom key saved" else "Using built-in key"

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            rotaryScrollableBehavior = RotaryScrollableDefaults.behavior(listState),
        ) {
            item { ListHeader { Text("Settings", fontWeight = FontWeight.Bold) } }
            item {
                Text(
                    text = "Search radius",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
            }
            items(RADIUS_OPTIONS_MILES) { miles ->
                val selected = miles == radiusMiles
                Chip(
                    onClick = { onRadiusSelected(miles) },
                    label = { Text(radiusLabel(miles)) },
                    icon = if (selected) {
                        { Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color.Black) }
                    } else {
                        null
                    },
                    colors = if (selected) {
                        ChipDefaults.primaryChipColors(backgroundColor = MtaPastelBlue, contentColor = Color.Black)
                    } else {
                        ChipDefaults.secondaryChipColors()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Chip(
                    onClick = onEditApiKey,
                    label = { Text("MTA API key") },
                    secondaryLabel = { Text(keyStatus) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                CompactButton(
                    onClick = onDone,
                    colors = ButtonDefaults.buttonColors(backgroundColor = MtaPastelBlue),
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Done", tint = Color.Black)
                }
            }
        }
    }
}
