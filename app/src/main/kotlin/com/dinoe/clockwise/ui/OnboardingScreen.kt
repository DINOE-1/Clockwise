package com.dinoe.clockwise.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dinoe.clockwise.R
import kotlinx.coroutines.launch

private const val PREFERENCES_NAME = "onboarding"
private const val COMPLETED_KEY = "completed"

class OnboardingPreferences(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val shouldShow: Boolean
        get() = !preferences.getBoolean(COMPLETED_KEY, false)

    fun markCompleted() {
        preferences.edit().putBoolean(COMPLETED_KEY, true).apply()
    }
}

private data class OnboardingTutorialPage(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    @DrawableRes val imageRes: Int,
)

private val tutorialPages = listOf(
    OnboardingTutorialPage(
        R.string.onboarding_calendar_month_title,
        R.string.onboarding_calendar_month_body,
        R.drawable.onboarding_calendar_month,
    ),
    OnboardingTutorialPage(
        R.string.onboarding_calendar_day_title,
        R.string.onboarding_calendar_day_body,
        R.drawable.onboarding_calendar_day,
    ),
    OnboardingTutorialPage(
        R.string.onboarding_alarm_title,
        R.string.onboarding_alarm_body,
        R.drawable.onboarding_alarms,
    ),
    OnboardingTutorialPage(
        R.string.onboarding_ai_title,
        R.string.onboarding_ai_body,
        R.drawable.onboarding_ai,
    ),
    OnboardingTutorialPage(
        R.string.onboarding_tags_title,
        R.string.onboarding_tags_body,
        R.drawable.onboarding_tags,
    ),
    OnboardingTutorialPage(
        R.string.onboarding_settings_title,
        R.string.onboarding_settings_body,
        R.drawable.onboarding_settings,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val themePreferences = remember(context) { ThemePreferences(context.applicationContext) }
    val languagePreferences = remember(context) { LanguagePreferences(context.applicationContext) }
    val themeMode by themePreferences.mode.collectAsStateWithLifecycle(
        initialValue = ThemeMode.SYSTEM,
    )
    var language by remember { mutableStateOf(languagePreferences.language) }
    val pageCount = tutorialPages.size + 1
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val currentPage = pagerState.currentPage
    val isLastPage = currentPage == pageCount - 1

    val goToPage: (Int) -> Unit = { target ->
        scope.launch { pagerState.animateScrollToPage(target.coerceIn(0, pageCount - 1)) }
    }

    BackHandler(enabled = currentPage > 0) { goToPage(currentPage - 1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.onboarding_title)) },
                actions = {
                    TextButton(onClick = onFinish) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PageIndicator(pageCount = pageCount, currentPage = currentPage)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (currentPage > 0) {
                        OutlinedButton(onClick = { goToPage(currentPage - 1) }) {
                            Text(stringResource(R.string.onboarding_back))
                        }
                    }
                    Button(
                        onClick = {
                            if (isLastPage) onFinish() else goToPage(currentPage + 1)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(
                                if (isLastPage) {
                                    R.string.onboarding_start
                                } else {
                                    R.string.onboarding_next
                                },
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) { page ->
            if (page == 0) {
                SetupPage(
                    themeMode = themeMode,
                    language = language,
                    onThemeSelected = { mode ->
                        scope.launch { themePreferences.setMode(mode) }
                    },
                    onLanguageSelected = { selected ->
                        if (selected != language) {
                            languagePreferences.setLanguage(selected)
                            language = selected
                            activity?.recreate()
                        }
                    },
                )
            } else {
                TutorialPage(
                    page = tutorialPages[page - 1],
                    showSwipeHint = page < pageCount - 1,
                )
            }
        }
    }
}

@Composable
private fun PageIndicator(pageCount: Int, currentPage: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .height(6.dp)
                    .width(if (selected) 20.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
            )
        }
    }
}

@Composable
private fun SetupPage(
    themeMode: ThemeMode,
    language: AppLanguage,
    onThemeSelected: (ThemeMode) -> Unit,
    onLanguageSelected: (AppLanguage) -> Unit,
) {
    val context = LocalContext.current
    val lightScheme = remember(context) { systemLightScheme(context) }
    val darkScheme = remember(context) { systemDarkScheme(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_setup_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.onboarding_setup_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        SettingsSectionHeader(stringResource(R.string.settings_section_appearance))
        SettingsCard {
            Column {
                SelectableOptionRow(
                    label = stringResource(R.string.theme_system),
                    selected = themeMode == ThemeMode.SYSTEM,
                    swatches = listOf(Color(0xFFFEF7FF), Color(0xFF141218)),
                    onSelect = { onThemeSelected(ThemeMode.SYSTEM) },
                )
                SettingsDivider()
                SelectableOptionRow(
                    label = stringResource(R.string.theme_light),
                    selected = themeMode == ThemeMode.LIGHT,
                    swatches = listOf(lightScheme.surface, lightScheme.primary),
                    onSelect = { onThemeSelected(ThemeMode.LIGHT) },
                )
                SettingsDivider()
                SelectableOptionRow(
                    label = stringResource(R.string.theme_dark),
                    selected = themeMode == ThemeMode.DARK,
                    swatches = listOf(darkScheme.surface, darkScheme.primary),
                    onSelect = { onThemeSelected(ThemeMode.DARK) },
                )
                SettingsDivider()
                SelectableOptionRow(
                    label = stringResource(R.string.theme_gruvbox),
                    selected = themeMode == ThemeMode.GRUVBOX,
                    swatches = listOf(
                        Color(0xFF1D2021),
                        Color(0xFFD8A657),
                        Color(0xFF89B482),
                    ),
                    onSelect = { onThemeSelected(ThemeMode.GRUVBOX) },
                )
            }
        }

        SettingsSectionHeader(stringResource(R.string.settings_section_language))
        SettingsCard {
            Column {
                SelectableOptionRow(
                    label = stringResource(R.string.language_system),
                    selected = language == AppLanguage.SYSTEM,
                    onSelect = { onLanguageSelected(AppLanguage.SYSTEM) },
                )
                SettingsDivider()
                SelectableOptionRow(
                    label = stringResource(R.string.language_traditional_chinese),
                    selected = language == AppLanguage.TRADITIONAL_CHINESE,
                    onSelect = { onLanguageSelected(AppLanguage.TRADITIONAL_CHINESE) },
                )
                SettingsDivider()
                SelectableOptionRow(
                    label = stringResource(R.string.language_english),
                    selected = language == AppLanguage.ENGLISH,
                    onSelect = { onLanguageSelected(AppLanguage.ENGLISH) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_setup_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TutorialPage(page: OnboardingTutorialPage, showSwipeHint: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(page.titleRes),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(page.bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Image(
                painter = painterResource(page.imageRes),
                contentDescription = stringResource(page.titleRes),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
            )
        }
        if (showSwipeHint) {
            Text(
                text = stringResource(R.string.onboarding_swipe_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
