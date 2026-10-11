package com.example.wellnessgame

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wellnessgame.auth.social.SignInHost
import com.example.wellnessgame.data.health.HealthConnectProvider
import com.example.wellnessgame.ui.account.AccountScreen
import com.example.wellnessgame.ui.home.HomeActions
import com.example.wellnessgame.ui.home.HomeScreen
import com.example.wellnessgame.ui.home.HomeViewModel
import com.example.wellnessgame.ui.login.LoginActions
import com.example.wellnessgame.ui.login.LoginScreen
import com.example.wellnessgame.ui.login.LoginViewModel
import com.example.wellnessgame.ui.theme.WellnessTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container get() = (application as WellnessApp).container

    /** Health Connect 권한 화면의 "개인정보처리방침" 링크로 열렸는지. */
    private val showRationale = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        showRationale.value = isRationaleIntent(intent)
        setContent {
            WellnessTheme {
                WellnessApp(container, signInHost = { this }, onOpenIntent = ::openIntent)
                if (showRationale.value) {
                    HealthRationaleDialog { showRationale.value = false }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isRationaleIntent(intent)) showRationale.value = true
    }

    private fun isRationaleIntent(intent: Intent?) =
        intent?.action == "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" ||
            intent?.action == ACTION_VIEW_PERMISSION_USAGE

    private fun openIntent(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // 스토어·설정 앱이 없는 기기: 조용히 무시한다.
        }
    }
}

private enum class Route { HOME, LOGIN, ACCOUNT }

/** Intent.ACTION_VIEW_PERMISSION_USAGE(API 29+). 문자열로 비교하므로 API 28에서도 안전하다. */
private const val ACTION_VIEW_PERMISSION_USAGE = "android.intent.action.VIEW_PERMISSION_USAGE"

@Composable
private fun WellnessApp(container: AppContainer, signInHost: SignInHost, onOpenIntent: (Intent) -> Unit) {
    val homeViewModel: HomeViewModel = viewModel {
        HomeViewModel(container.dashboard, container.session, container.settings)
    }
    val home by homeViewModel.state.collectAsStateWithLifecycle()
    val session by container.session.state.collectAsStateWithLifecycle()
    var route by rememberSaveable { mutableStateOf(Route.HOME) }
    val scope = rememberCoroutineScope()

    LifecycleEventEffect(Lifecycle.Event.ON_START) { homeViewModel.onForeground() }

    // 로그인에 성공하면 홈으로, 세션이 강제로 끝나면(401·만료) 로그인 화면으로 안내한다(iOS와 같음).
    LaunchedEffect(session.isSignedIn, session.reloginRequired) {
        if (session.isSignedIn && route == Route.LOGIN) route = Route.HOME
        if (!session.isSignedIn && route == Route.ACCOUNT) route = Route.HOME
        if (session.reloginRequired) route = Route.LOGIN
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { homeViewModel.onPermissionsResult() }

    // 로그인 화면을 닫을 때는 재로그인 안내도 함께 닫는다.
    val goHome = {
        container.session.dismissReloginNotice()
        route = Route.HOME
    }

    BackHandler(enabled = route != Route.HOME, onBack = goHome)

    when (route) {
        Route.HOME -> HomeScreen(
            state = home,
            actions = HomeActions(
                onRefresh = { homeViewModel.refresh(force = true) },
                onOpenLogin = { route = Route.LOGIN },
                onOpenAccount = { route = Route.ACCOUNT },
                onRequestHealthPermission = {
                    scope.launch { permissionLauncher.launch(homeViewModel.permissionsToRequest()) }
                },
                onInstallHealthConnect = {
                    val uri = "market://details?id=${HealthConnectProvider.PROVIDER_PACKAGE}&url=healthconnect%3A%2F%2Fonboarding".toUri()
                    onOpenIntent(Intent(Intent.ACTION_VIEW, uri))
                },
                onToggleMock = homeViewModel::setUseMockData,
            ),
        )
        Route.LOGIN -> {
            val loginViewModel: LoginViewModel = viewModel {
                LoginViewModel(container.auth, container.deviceRegion())
            }
            val login by loginViewModel.state.collectAsStateWithLifecycle()
            LoginScreen(
                state = login,
                showReloginNotice = session.reloginRequired,
                actions = LoginActions(
                    onUsernameChange = loginViewModel::onUsernameChange,
                    onPasswordChange = loginViewModel::onPasswordChange,
                    onLogIn = loginViewModel::logIn,
                    onSignUp = loginViewModel::signUp,
                    onSocial = { loginViewModel.socialSignIn(it, signInHost) },
                    onToggleOtherProviders = loginViewModel::toggleOtherProviders,
                    onClose = goHome,
                ),
            )
        }
        Route.ACCOUNT -> AccountScreen(
            session = session,
            onLogOut = {
                scope.launch {
                    container.auth.signOut()
                    container.settings.setLastSyncedAt(null)
                }
                route = Route.HOME
            },
            onOpenHealthSettings = { onOpenIntent(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) },
            onClose = { route = Route.HOME },
        )
    }
}

@Composable
private fun HealthRationaleDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
        title = { Text(stringResource(R.string.rationale_title)) },
        text = { Text(stringResource(R.string.rationale_body)) },
    )
}
