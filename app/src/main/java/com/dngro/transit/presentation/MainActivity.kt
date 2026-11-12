package com.dngro.transit.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.ButtonDefaults
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
import com.dngro.transit.presentation.theme.MtaPastelBlue
import com.dngro.transit.presentation.theme.MtaPastelGold
import com.dngro.transit.presentation.theme.MtaPastelGreen
import com.dngro.transit.presentation.theme.MtaPastelPurple
import com.dngro.transit.presentation.theme.TransitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)

        setContent {
            TransitTheme {
                val viewModel: BusViewModel = viewModel()
                val context = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current
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
                    // while the screen is on. Stops when the app is backgrounded.
                    LaunchedEffect(lifecycleOwner) {
                        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.refreshIfStale()
                            while (true) {
                                delay(AUTO_REFRESH_CHECK_MS)
                                viewModel.refreshIfStale(retryErrors = false)
                            }
                        }
                    }
                    BusApp(viewModel)
                } else {
                    PermissionScreen(onAllow = { permissionLauncher.launch(LOCATION_PERMISSIONS) })
                }
            }
        }
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

private val clockFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

private fun Context.hasLocationPermission(): Boolean =
    LOCATION_PERMISSIONS.any {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

@Composable
fun BusApp(viewModel: BusViewModel) {
    val listState = rememberScalingLazyListState()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    // Recompute "N min away" between network refreshes so labels never go stale.
    val now by produceState(initialValue = Instant.now()) {
        while (true) {
            delay(CLOCK_TICK_MS)
            value = Instant.now()
        }
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
                    Spacer(modifier = Modifier.height(16.dp))
                    CompactButton(
                        onClick = { viewModel.refresh() },
                        colors = ButtonDefaults.buttonColors(backgroundColor = MtaPastelBlue),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.Black)
                    }
                }
                is UiState.Success -> {
                    ScalingLazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
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
                        if (state.stale) {
                            item {
                                Text(
                                    text = "Couldn't update · showing last results",
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
                                onRoutePinToggle = { viewModel.toggleRoutePin(route.routeShortName) }
                            )
                        }
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CompactButton(
                                    onClick = { viewModel.refresh() },
                                    enabled = !isRefreshing,
                                    colors = ButtonDefaults.buttonColors(backgroundColor = MtaPastelBlue),
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.Black)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BusRouteGroup(
    route: BusRouteUiModel,
    expandedRouteIds: Set<String>,
    now: Instant,
    onRouteToggle: () -> Unit,
    onRoutePinToggle: () -> Unit,
) {
    val isRouteExpanded = expandedRouteIds.contains(route.routeShortName)
    val badgeColor = if (route.routeShortName.startsWith("SBS", ignoreCase = true) || route.routeShortName.contains("SBS", ignoreCase = true)) {
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
                // Pin / Star Button Toggle
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onRoutePinToggle() }
                        .padding(4.dp),
                ) {
                    Icon(
                        imageVector = if (route.isPinned) Icons.Default.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (route.isPinned) "Unpin Route" else "Pin Route",
                        tint = if (route.isPinned) MtaPastelGold else MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
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
            )
            .pointerInput(route.routeShortName) {
                detectTapGestures(
                    onLongPress = { onRoutePinToggle() },
                )
            },
    ) {
        if (isRouteExpanded) {
            Column {
                route.directions.forEach { direction ->
                    DirectionItem(direction = direction, now = now)
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
    direction: BusDirectionUiModel,
    now: Instant,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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

        if (direction.arrivals.isEmpty()) {
            Text(
                text = "No scheduled buses",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
            )
        } else {
            direction.arrivals.forEach { arrival ->
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
