package io.github.seongkyulim.wellnessgame.ui.login

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.seongkyulim.wellnessgame.R
import io.github.seongkyulim.wellnessgame.domain.GrowthStage
import io.github.seongkyulim.wellnessgame.domain.LoginProvider
import io.github.seongkyulim.wellnessgame.ui.components.CelButton
import io.github.seongkyulim.wellnessgame.ui.components.DangerNotice
import io.github.seongkyulim.wellnessgame.ui.components.SproutyAvatar
import io.github.seongkyulim.wellnessgame.ui.components.displayName
import io.github.seongkyulim.wellnessgame.ui.components.rateLimitedText
import io.github.seongkyulim.wellnessgame.ui.theme.Wellness
import io.github.seongkyulim.wellnessgame.ui.theme.WellnessMetrics

data class LoginActions(
    val onUsernameChange: (String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onLogIn: () -> Unit,
    val onSignUp: () -> Unit,
    val onSocial: (LoginProvider) -> Unit,
    val onToggleOtherProviders: () -> Unit,
    val onClose: () -> Unit,
)

/** 소셜 버튼의 테스트 태그(노출 순서 UI 테스트용). */
fun socialButtonTag(provider: LoginProvider) = "social_${provider.storageKey}"

@Composable
fun LoginScreen(state: LoginUiState, showReloginNotice: Boolean, actions: LoginActions, modifier: Modifier = Modifier) {
    val colors = Wellness.colors
    Column(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = actions.onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close), tint = colors.textPrimary)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.login_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.textPrimary,
                )
                Text(stringResource(R.string.login_subtitle), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            }
            Spacer(Modifier.width(12.dp))
            SproutyAvatar(GrowthStage.SPROUT, Modifier.size(84.dp))
        }

        if (showReloginNotice) DangerNotice(stringResource(R.string.status_session_expired))

        CredentialField(
            label = stringResource(R.string.username),
            value = state.username,
            hint = stringResource(R.string.username_hint),
            onValueChange = actions.onUsernameChange,
            isPassword = false,
            tag = "username",
        )
        CredentialField(
            label = stringResource(R.string.password),
            value = state.password,
            hint = stringResource(R.string.password_hint),
            onValueChange = actions.onPasswordChange,
            isPassword = true,
            tag = "password",
        )
        state.error?.let { DangerNotice(errorText(it)) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CelButton(stringResource(R.string.action_log_in), actions.onLogIn, enabled = state.canSubmitCredentials)
            CelButton(
                stringResource(R.string.action_sign_up),
                actions.onSignUp,
                fill = colors.surface,
                textColor = colors.textPrimary,
                enabled = state.canSubmitCredentials,
            )
        }

        Divider()

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.policy.primary.forEach { SocialButton(it, state, actions) }
            if (state.policy.secondary.isNotEmpty()) {
                TextButton(onClick = actions.onToggleOtherProviders, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.other_sign_in), style = MaterialTheme.typography.labelLarge, color = colors.textPrimary)
                    Icon(
                        if (state.showOtherProviders) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = colors.textPrimary,
                    )
                }
                if (state.showOtherProviders) state.policy.secondary.forEach { SocialButton(it, state, actions) }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SocialButton(provider: LoginProvider, state: LoginUiState, actions: LoginActions) {
    val colors = Wellness.colors
    val configured = provider in state.configured
    val (fill, label) = when (provider) {
        LoginProvider.KAKAO -> colors.kakao to stringResource(R.string.sign_in_with_kakao)
        LoginProvider.NAVER -> colors.naver to stringResource(R.string.sign_in_with_naver)
        else -> colors.surface to stringResource(R.string.sign_in_with_google)
    }
    Column(Modifier.testTag(socialButtonTag(provider))) {
        CelButton(
            text = label,
            onClick = { actions.onSocial(provider) },
            fill = fill,
            textColor = if (provider == LoginProvider.GOOGLE) colors.textPrimary else colors.onPop,
            enabled = configured && !state.isBusy,
        )
        if (!configured) {
            Text(
                stringResource(R.string.provider_not_configured, provider.displayName()),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun Divider() {
    val colors = Wellness.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(2.dp).background(colors.fieldBorder.copy(alpha = 0.5f)))
        Text(
            stringResource(R.string.or_social),
            modifier = Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
        )
        Box(Modifier.weight(1f).height(2.dp).background(colors.fieldBorder.copy(alpha = 0.5f)))
    }
}

@Composable
private fun CredentialField(
    label: String,
    value: String,
    hint: String,
    onValueChange: (String) -> Unit,
    isPassword: Boolean,
    tag: String,
) {
    val colors = Wellness.colors
    val shape = RoundedCornerShape(WellnessMetrics.chipRadius)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.textPrimary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.textPrimary),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Ascii,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag)
                .semantics { },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .background(colors.surface, shape)
                        .border(WellnessMetrics.thinLine, colors.fieldBorder, shape)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(hint, style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun errorText(error: LoginError): String = when (error) {
    is LoginError.Server -> error.message ?: stringResource(R.string.login_error_generic)
    is LoginError.RateLimited -> rateLimitedText(error.retryAfterSeconds)
    LoginError.Network -> stringResource(R.string.status_network)
    is LoginError.Social -> stringResource(R.string.login_error_social, error.provider.displayName())
}
