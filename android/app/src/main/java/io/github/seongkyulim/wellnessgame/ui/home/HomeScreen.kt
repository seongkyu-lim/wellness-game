package com.example.wellnessgame.ui.home

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.wellnessgame.BuildConfig
import com.example.wellnessgame.R
import com.example.wellnessgame.data.network.CharacterState
import com.example.wellnessgame.domain.DailyQuestProgress
import com.example.wellnessgame.domain.GrowthStage
import com.example.wellnessgame.domain.LevelProgress
import com.example.wellnessgame.domain.WorkoutKind
import com.example.wellnessgame.sync.HealthAccess
import com.example.wellnessgame.ui.components.CelButton
import com.example.wellnessgame.ui.components.CelCard
import com.example.wellnessgame.ui.components.DangerNotice
import com.example.wellnessgame.ui.components.PillBadge
import com.example.wellnessgame.ui.components.ProgressBar
import com.example.wellnessgame.ui.components.QuestCard
import com.example.wellnessgame.ui.components.SectionHeader
import com.example.wellnessgame.ui.components.SproutyAvatar
import com.example.wellnessgame.ui.components.StatTile
import com.example.wellnessgame.ui.components.celOutline
import com.example.wellnessgame.ui.components.displayName
import com.example.wellnessgame.ui.components.durationText
import com.example.wellnessgame.ui.components.labelRes
import com.example.wellnessgame.ui.components.MessageCard
import com.example.wellnessgame.ui.components.rateLimitedText
import com.example.wellnessgame.ui.theme.Wellness
import com.example.wellnessgame.ui.theme.WellnessMetrics
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** 홈 화면 콜백 묶음. */
data class HomeActions(
    val onRefresh: () -> Unit,
    val onOpenLogin: () -> Unit,
    val onOpenAccount: () -> Unit,
    val onRequestHealthPermission: () -> Unit,
    val onInstallHealthConnect: () -> Unit,
    val onToggleMock: (Boolean) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeUiState, actions: HomeActions, modifier: Modifier = Modifier) {
    val colors = Wellness.colors
    PullToRefreshBox(
        isRefreshing = state.isLoading,
        onRefresh = actions.onRefresh,
        modifier = modifier.fillMaxSize().background(colors.background),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(WellnessMetrics.sectionSpacing),
        ) {
            Header(state, actions)
            CharacterHero(state)
            CharacterDetails(state, actions)
            HealthNotice(state, actions)
            Quests(state)
            Rewards(state)
            Footer(state, actions)
        }
    }
}

@Composable
private fun Header(state: HomeUiState, actions: HomeActions) {
    val colors = Wellness.colors
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    append("Wellness ")
                    withStyle(SpanStyle(color = colors.primary)) { append("Game") }
                },
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                color = colors.textPrimary,
            )
            Text(
                LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        }
        val signedIn = state.session.isSignedIn
        Box(Modifier.width(132.dp)) {
            CelButton(
                text = if (signedIn) {
                    state.session.provider?.displayName() ?: stringResource(R.string.account_title)
                } else {
                    stringResource(R.string.action_log_in)
                },
                onClick = if (signedIn) actions.onOpenAccount else actions.onOpenLogin,
                fill = colors.surface,
                textColor = colors.textPrimary,
            )
        }
    }
}

@Composable
private fun CharacterHero(state: HomeUiState) {
    val colors = Wellness.colors
    val character = state.character
    val level = character?.level ?: 1
    val stage = GrowthStage.forLevel(level)
    val bubble = when {
        !state.session.isSignedIn -> stringResource(R.string.bubble_sign_in)
        character == null -> stringResource(R.string.bubble_sync)
        else -> GrowthStage.nextAfter(level)?.let {
            stringResource(R.string.bubble_next, it.minLevel, stringResource(it.labelRes()))
        } ?: stringResource(R.string.bubble_max)
    }
    val bob = rememberBob()

    Box(
        Modifier
            .fillMaxWidth()
            .padding(end = WellnessMetrics.heroShadow, bottom = WellnessMetrics.heroShadow)
            .celOutline(colors.sky, colors.ink, colors.popShadow, WellnessMetrics.heroRadius, WellnessMetrics.heroShadow)
            .clip(RoundedCornerShape(WellnessMetrics.heroRadius))
            .height(250.dp),
    ) {
        // 하프톤 점 + 두 겹 언덕
        Canvas(Modifier.fillMaxSize()) {
            val dot = 3.dp.toPx()
            val gap = 18.dp.toPx()
            var y = gap / 2
            while (y < size.height * 0.6f) {
                var x = gap / 2 + if (((y / gap).toInt()) % 2 == 0) 0f else gap / 2
                while (x < size.width) {
                    drawCircle(colors.surface.copy(alpha = if (colors.isDark) 0.12f else 0.5f), dot / 2, Offset(x, y))
                    x += gap
                }
                y += gap
            }
            val back = Path().apply {
                moveTo(0f, size.height * 0.72f)
                quadraticTo(size.width * 0.3f, size.height * 0.58f, size.width * 0.62f, size.height * 0.7f)
                quadraticTo(size.width * 0.85f, size.height * 0.78f, size.width, size.height * 0.66f)
                lineTo(size.width, size.height); lineTo(0f, size.height); close()
            }
            drawPath(back, colors.hill)
            val front = Path().apply {
                moveTo(0f, size.height * 0.86f)
                quadraticTo(size.width * 0.5f, size.height * 0.74f, size.width, size.height * 0.88f)
                lineTo(size.width, size.height); lineTo(0f, size.height); close()
            }
            drawPath(front, colors.hillDeep)
        }
        // 말풍선
        Text(
            bubble,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp)
                .fillMaxWidth(0.62f)
                .padding(end = 3.dp, bottom = 3.dp)
                .celOutline(colors.surface, colors.ink, colors.popShadow, radius = 16.dp, shadowOffset = 3.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
        )
        if (state.gainedXp > 0 || state.levelUp) {
            Column(Modifier.align(Alignment.TopEnd).padding(14.dp), horizontalAlignment = Alignment.End) {
                if (state.levelUp) PillBadge(stringResource(R.string.level_up), fill = colors.pink)
                if (state.gainedXp > 0) {
                    Spacer(Modifier.height(6.dp))
                    PillBadge(stringResource(R.string.gained_xp, state.gainedXp))
                }
            }
        }
        SproutyAvatar(
            stage = stage,
            contentDescription = stringResource(R.string.avatar_description, stringResource(stage.labelRes())),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 30.dp)
                .size(130.dp)
                .graphicsLayer {
                    translationY = bob.value * -6.dp.toPx()
                    rotationZ = bob.value * 2f
                },
        )
        // 이름표 + 레벨 칩
        Row(
            Modifier.align(Alignment.BottomStart).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillBadge(stringResource(R.string.mascot_name), Modifier.rotate(-4f))
            Spacer(Modifier.width(8.dp))
            PillBadge(
                character?.let {
                    stringResource(R.string.level_chip, it.level, stringResource(GrowthStage.forLevel(it.level).labelRes()))
                } ?: stringResource(R.string.not_synced_yet),
                fill = colors.mint,
            )
        }
    }
}

/** 둥실거림(약 2.6초). 시스템 애니메이션이 꺼져 있으면 멈춘다. */
@Composable
private fun rememberBob(): androidx.compose.runtime.State<Float> {
    val context = LocalContext.current
    val animationsOff = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    if (animationsOff) return remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val transition = rememberInfiniteTransition(label = "bob")
    return transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bobValue",
    )
}

@Composable
private fun CharacterDetails(state: HomeUiState, actions: HomeActions) {
    val colors = Wellness.colors
    val character = state.character
    when {
        !state.session.isSignedIn -> {
            val expired = state.session.reloginRequired || state.status == HomeStatus.SessionExpired
            MessageCard(
                title = stringResource(R.string.signin_prompt_title),
                body = if (expired) null else stringResource(R.string.signin_prompt_body),
            ) {
                if (expired) DangerNotice(stringResource(R.string.status_session_expired))
                CelButton(stringResource(R.string.signin_prompt_button), onClick = actions.onOpenLogin)
            }
        }
        character != null -> {
            XpCard(character)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("STR", stringResource(R.string.stat_strength), character.stats.str, colors.statStrength, Modifier.weight(1f))
                StatTile("VIT", stringResource(R.string.stat_vitality), character.stats.vit, colors.statVitality, Modifier.weight(1f))
                StatTile("DISC", stringResource(R.string.stat_discipline), character.stats.discipline, colors.statDiscipline, Modifier.weight(1f))
                StatTile("REC", stringResource(R.string.stat_recovery), character.stats.recovery, colors.statRecovery, Modifier.weight(1f))
            }
        }
        else -> MessageCard(stringResource(R.string.waiting_title), stringResource(R.string.waiting_body)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillBadge(stringResource(R.string.level_chip, 1, stringResource(R.string.stage_seed)), fill = colors.lilac)
                GrowthStage.nextAfter(1)?.let {
                    PillBadge(stringResource(R.string.next_stage_chip, stringResource(it.labelRes()), it.minLevel), fill = colors.mint)
                }
            }
        }
    }
}

@Composable
private fun XpCard(character: CharacterState) {
    val colors = Wellness.colors
    val progress by animateFloatAsState(LevelProgress.fraction(character.currentXp, character.nextLevelXp), tween(800), label = "xp")
    val xpText = stringResource(R.string.xp_value, character.currentXp, character.nextLevelXp)
    CelCard(radius = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.level_short, character.level),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(xpText, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            }
            ProgressBar(progress, description = xpText)
            Text(
                stringResource(R.string.xp_total, character.totalXp),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
        }
    }
}

/** 건강 데이터 안내 카드 한 종류: 제목·본문과 (있으면) 버튼 문구·동작. */
private data class HealthNoticeContent(val title: Int, val body: Int, val button: Int? = null, val onClick: () -> Unit = {})

@Composable
private fun HealthNotice(state: HomeUiState, actions: HomeActions) {
    if (!state.session.isSignedIn || state.useMockData) return
    val notice = when (state.healthAccess) {
        HealthAccess.PERMISSION_REQUIRED -> HealthNoticeContent(
            R.string.health_permission_title, R.string.health_permission_body,
            R.string.health_permission_button, actions.onRequestHealthPermission,
        )
        HealthAccess.PROVIDER_UPDATE_REQUIRED -> HealthNoticeContent(
            R.string.health_update_title, R.string.health_update_body,
            R.string.health_update_button, actions.onInstallHealthConnect,
        )
        HealthAccess.UNSUPPORTED -> HealthNoticeContent(R.string.health_unsupported_title, R.string.health_unsupported_body)
        else -> return
    }
    MessageCard(
        title = stringResource(notice.title),
        body = stringResource(notice.body),
        titleStyle = MaterialTheme.typography.titleMedium,
    ) {
        notice.button?.let { CelButton(stringResource(it), onClick = notice.onClick) }
    }
}

@Composable
private fun Quests(state: HomeUiState) {
    val quests = state.quests ?: return
    val colors = Wellness.colors
    val number = remember { NumberFormat.getIntegerInstance() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(stringResource(R.string.quests_title))
        val stepsText = stringResource(
            R.string.quest_steps_value,
            number.format(quests.steps),
            number.format(DailyQuestProgress.STEPS_GOAL),
        )
        QuestCard("👟", colors.mint, stringResource(R.string.quest_steps), stepsText, progress = quests.stepsProgress, progressDescription = stepsText)

        val workoutKinds = state.snapshot?.workouts.orEmpty()
            .mapNotNull { WorkoutKind.fromServer(it.workoutType) }
            .distinct()
        val workoutLabel = workoutKinds.map { stringResource(it.labelRes()) }.joinToString(" · ")
        val workoutText = stringResource(R.string.quest_workout_value, quests.workoutMinutes, DailyQuestProgress.WORKOUT_GOAL_MINUTES)
        QuestCard(
            "🏃",
            colors.peach,
            stringResource(R.string.quest_workout),
            if (workoutLabel.isEmpty()) workoutText else "$workoutText · $workoutLabel",
            progress = quests.workoutProgress,
            progressDescription = workoutText,
        )

        val sleepText = stringResource(
            R.string.quest_sleep_value,
            durationText(quests.sleepMinutes),
            durationText(DailyQuestProgress.SLEEP_GOAL_MINUTES),
        )
        QuestCard("🌙", colors.lilac, stringResource(R.string.quest_sleep), sleepText, progress = quests.sleepProgress, progressDescription = sleepText)
    }
}

@Composable
private fun Rewards(state: HomeUiState) {
    if (state.results.isEmpty()) return
    val colors = Wellness.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(stringResource(R.string.rewards_title))
        state.results.forEach { result ->
            QuestCard(
                glyph = if (result.duplicate) "✔️" else "✨",
                tint = if (result.duplicate) colors.lilac else colors.yellow,
                title = result.message,
                subtitle = result.source,
            ) {
                if (result.duplicate) {
                    PillBadge(stringResource(R.string.already_counted), fill = colors.lilac)
                } else {
                    PillBadge(stringResource(R.string.gained_xp, result.gainedXp))
                }
            }
        }
    }
}

@Composable
private fun Footer(state: HomeUiState, actions: HomeActions) {
    val colors = Wellness.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(statusText(state.status), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        val lastSynced = state.lastSyncedAt?.let {
            val formatted = it.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
            stringResource(R.string.last_synced, formatted)
        } ?: stringResource(R.string.never_synced)
        Text(lastSynced, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        if (BuildConfig.DEBUG) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.mock_toggle),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.useMockData,
                    onCheckedChange = actions.onToggleMock,
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.primary),
                )
            }
        }
    }
}

@Composable
private fun statusText(status: HomeStatus): String = when (status) {
    HomeStatus.Idle -> stringResource(R.string.status_idle)
    HomeStatus.SignInRequired -> stringResource(R.string.status_sign_in)
    HomeStatus.MockPreview -> stringResource(R.string.status_mock_preview)
    is HomeStatus.Synced -> stringResource(if (status.levelUp) R.string.status_level_up else R.string.status_synced)
    HomeStatus.HealthPermissionRequired -> stringResource(R.string.status_permission)
    HomeStatus.HealthUnavailable -> stringResource(R.string.health_unsupported_title)
    HomeStatus.HealthReadFailed -> stringResource(R.string.status_health_error)
    HomeStatus.SessionExpired -> stringResource(R.string.status_session_expired)
    is HomeStatus.RateLimited -> rateLimitedText(status.retryAfterSeconds)
    HomeStatus.NetworkError -> stringResource(R.string.status_network)
    is HomeStatus.ServerError -> stringResource(R.string.status_server_error, status.message ?: stringResource(R.string.error_unknown))
}
