package io.github.seongkyulim.wellnessgame.ui.login

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.seongkyulim.wellnessgame.domain.LoginProvider
import io.github.seongkyulim.wellnessgame.domain.LoginProviderPolicy
import io.github.seongkyulim.wellnessgame.ui.theme.WellnessTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
// 실제 WellnessApp(Keystore·WorkManager)을 띄우지 않고 화면만 검증한다.
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h2000dp", application = Application::class)
class LoginScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val noActions = LoginActions({}, {}, {}, {}, {}, {}, {})

    private fun setContent(region: String?, configured: Set<LoginProvider> = LoginProviderPolicy.SOCIAL_PROVIDERS.toSet()) {
        compose.setContent {
            var state by mutableStateOf(
                LoginUiState(policy = LoginProviderPolicy.forRegion(region), configured = configured),
            )
            WellnessTheme(darkTheme = false) {
                LoginScreen(
                    state = state,
                    showReloginNotice = false,
                    actions = noActions.copy(onToggleOtherProviders = {
                        state = state.copy(showOtherProviders = !state.showOtherProviders)
                    }),
                )
            }
        }
    }

    private fun top(provider: LoginProvider) =
        compose.onNodeWithTag(socialButtonTag(provider)).getUnclippedBoundsInRoot().top

    @Test
    fun korea_showsKakaoNaverGoogleInOrder_withoutOtherOptions() {
        setContent("KR")
        LoginProviderPolicy.SOCIAL_PROVIDERS.forEach { compose.onNodeWithTag(socialButtonTag(it)).assertExists() }
        assertTrue(top(LoginProvider.KAKAO) < top(LoginProvider.NAVER))
        assertTrue(top(LoginProvider.NAVER) < top(LoginProvider.GOOGLE))
        compose.onNodeWithText("Other sign-in options").assertDoesNotExist()
    }

    @Test
    fun overseas_showsGoogleFirst_andRevealsKakaoNaverOnDemand() {
        setContent("US")
        compose.onNodeWithTag(socialButtonTag(LoginProvider.GOOGLE)).assertExists()
        compose.onNodeWithTag(socialButtonTag(LoginProvider.KAKAO)).assertDoesNotExist()
        compose.onNodeWithTag(socialButtonTag(LoginProvider.NAVER)).assertDoesNotExist()

        compose.onNodeWithText("Other sign-in options").performScrollTo().performClick()

        assertTrue(top(LoginProvider.GOOGLE) < top(LoginProvider.KAKAO))
        assertTrue(top(LoginProvider.KAKAO) < top(LoginProvider.NAVER))
    }

    @Test
    fun credentialFormIsAlwaysShown_andUnconfiguredProviderIsDisabled() {
        setContent(null, configured = emptySet())
        compose.onNodeWithTag("username").assertIsDisplayed()
        compose.onNodeWithTag("password").assertIsDisplayed()
        compose.onNodeWithText("Sign in with Google").assertIsNotEnabled()
        compose.onNodeWithText("Google sign-in isn't set up in this build.").assertExists()
    }
}
