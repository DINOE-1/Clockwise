package com.dinoe.clockwise.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dinoe.clockwise.R

private const val TAB_CALENDAR = 0
private const val TAB_ALARM = 1
private const val TAB_AI = 2
private const val TAB_TAGS = 3
private const val TAB_SETTINGS = 4

private const val TAB_TRANSITION_MILLIS = 300
private const val TAB_FADE_OUT_MILLIS = 150

@Composable
fun MainScreen() {
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_CALENDAR) }
    var pendingEditEventId by remember { mutableStateOf<Long?>(null) }
    var openAiSettingsRequest by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == TAB_CALENDAR,
                    onClick = { selectedTab = TAB_CALENDAR },
                    icon = {
                        Icon(
                            Icons.Filled.DateRange,
                            contentDescription = stringResource(R.string.tab_calendar),
                        )
                    },
                    label = { Text(stringResource(R.string.tab_calendar)) },
                )
                NavigationBarItem(
                    selected = selectedTab == TAB_ALARM,
                    onClick = { selectedTab = TAB_ALARM },
                    icon = {
                        Icon(
                            Icons.Filled.Notifications,
                            contentDescription = stringResource(R.string.tab_alarm),
                        )
                    },
                    label = { Text(stringResource(R.string.tab_alarm)) },
                )
                NavigationBarItem(
                    selected = selectedTab == TAB_AI,
                    onClick = { selectedTab = TAB_AI },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_ai),
                            contentDescription = stringResource(R.string.tab_ai),
                        )
                    },
                    label = { Text(stringResource(R.string.tab_ai)) },
                )
                NavigationBarItem(
                    selected = selectedTab == TAB_TAGS,
                    onClick = { selectedTab = TAB_TAGS },
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = stringResource(R.string.tab_tags),
                        )
                    },
                    label = { Text(stringResource(R.string.tab_tags)) },
                )
                NavigationBarItem(
                    selected = selectedTab == TAB_SETTINGS,
                    onClick = { selectedTab = TAB_SETTINGS },
                    icon = {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.tab_settings),
                        )
                    },
                    label = { Text(stringResource(R.string.tab_settings)) },
                )
            }
        },
        // Each tab screen owns its own window insets; only the tab bar is added here.
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
    ) { innerPadding ->
        Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
            AnimatedContent(
                targetState = selectedTab,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
                transitionSpec = {
                    // Higher tab indices enter from the right, lower ones from the left.
                    val direction = if (targetState > initialState) {
                        AnimatedContentTransitionScope.SlideDirection.Left
                    } else {
                        AnimatedContentTransitionScope.SlideDirection.Right
                    }
                    val slideSpec = tween<IntOffset>(
                        durationMillis = TAB_TRANSITION_MILLIS,
                        easing = FastOutSlowInEasing,
                    )
                    val enter = slideIntoContainer(direction, slideSpec) +
                        fadeIn(tween(TAB_TRANSITION_MILLIS, easing = FastOutSlowInEasing))
                    val exit = slideOutOfContainer(direction, slideSpec) +
                        fadeOut(tween(TAB_FADE_OUT_MILLIS))
                    enter togetherWith exit
                },
                label = "mainTabTransition",
            ) { tab ->
                when (tab) {
                    TAB_ALARM -> AlarmScreen(
                        onOpenEvent = { eventId ->
                            pendingEditEventId = eventId
                            selectedTab = TAB_CALENDAR
                        },
                    )

                    TAB_AI -> AiScreen(
                        onOpenSettings = {
                            openAiSettingsRequest = true
                            selectedTab = TAB_SETTINGS
                        },
                    )

                    TAB_TAGS -> TagsScreen()
                    TAB_SETTINGS -> SettingsScreen(
                        openAiSettingsRequest = openAiSettingsRequest,
                        onOpenAiSettingsRequestHandled = { openAiSettingsRequest = false },
                    )
                    else -> CalendarScreen(
                        pendingEventId = pendingEditEventId,
                        onPendingEventConsumed = { pendingEditEventId = null },
                    )
                }
            }
        }
    }
}
