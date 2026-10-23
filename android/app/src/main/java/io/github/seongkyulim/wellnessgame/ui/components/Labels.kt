package io.github.seongkyulim.wellnessgame.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.seongkyulim.wellnessgame.R
import io.github.seongkyulim.wellnessgame.domain.GrowthStage
import io.github.seongkyulim.wellnessgame.domain.LoginProvider
import io.github.seongkyulim.wellnessgame.domain.WorkoutKind

@StringRes
fun GrowthStage.labelRes(): Int = when (this) {
    GrowthStage.SEED -> R.string.stage_seed
    GrowthStage.SPROUT -> R.string.stage_sprout
    GrowthStage.SAPLING -> R.string.stage_sapling
    GrowthStage.YOUNG -> R.string.stage_young
    GrowthStage.TREE -> R.string.stage_tree
    GrowthStage.BLOSSOM -> R.string.stage_blossom
}

@StringRes
fun WorkoutKind.labelRes(): Int = when (this) {
    WorkoutKind.RUNNING -> R.string.workout_running
    WorkoutKind.WALKING -> R.string.workout_walking
    WorkoutKind.CYCLING -> R.string.workout_cycling
    WorkoutKind.SWIMMING -> R.string.workout_swimming
    WorkoutKind.STRENGTH_TRAINING -> R.string.workout_strength
    WorkoutKind.OTHER -> R.string.workout_other
}

/** 공급자 표시 이름(브랜드명은 번역하지 않는다). */
@Composable
fun LoginProvider.displayName(): String = when (this) {
    LoginProvider.GOOGLE -> "Google"
    LoginProvider.KAKAO -> "Kakao"
    LoginProvider.NAVER -> "Naver"
    LoginProvider.PASSWORD -> stringResource(R.string.account_title)
}

/** 분 → "7시간 30분" / "7h 30m". */
@Composable
fun durationText(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> stringResource(R.string.duration_minutes, rest)
        rest == 0 -> stringResource(R.string.duration_hours, hours)
        else -> stringResource(R.string.duration_hours_minutes, hours, rest)
    }
}

/** 429 안내. `Retry-After`가 없으면 "잠시 후" 문구. */
@Composable
fun rateLimitedText(retryAfterSeconds: Long?): String =
    retryAfterSeconds?.let { pluralStringResource(R.plurals.status_rate_limited, it.toInt(), it.toInt()) }
        ?: stringResource(R.string.status_rate_limited_unknown)
