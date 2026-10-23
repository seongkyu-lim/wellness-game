package com.example.wellnessgame

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import com.example.wellnessgame.auth.AuthRepository
import com.example.wellnessgame.auth.KeystoreTokenStore
import com.example.wellnessgame.auth.SessionManager
import com.example.wellnessgame.auth.social.GoogleSignInClient
import com.example.wellnessgame.auth.social.KakaoSignInClient
import com.example.wellnessgame.auth.social.NaverSignInClient
import com.example.wellnessgame.data.health.HealthConnectProvider
import com.example.wellnessgame.data.health.MockHealthDataProvider
import com.example.wellnessgame.data.network.ApiClient
import com.example.wellnessgame.data.network.NetworkModule
import com.example.wellnessgame.data.network.ServerConfig
import com.example.wellnessgame.data.settings.SettingsStore
import com.example.wellnessgame.domain.AppLanguage
import com.example.wellnessgame.domain.LoginProvider
import com.example.wellnessgame.sync.DashboardRepository
import com.example.wellnessgame.sync.DefaultDashboardRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.example.wellnessgame.data.network.buildTypeInterceptors

/** 수동 DI 컨테이너. 앱 프로세스당 하나(Application이 소유). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsStore(appContext)
    val session = SessionManager(KeystoreTokenStore(appContext), refreshScope = appScope)
    private val restoreJob = appScope.launch { session.restore() }

    suspend fun awaitSessionRestored() = restoreJob.join()

    val api: ApiClient = ApiClient(
        api = NetworkModule.api(
            baseUrl = ServerConfig.resolveBaseUrl(BuildConfig.API_BASE_URL, BuildConfig.DEBUG),
            client = NetworkModule.okHttpClient(language = ::acceptLanguage, extra = buildTypeInterceptors()),
        ),
        tokens = session,
    )

    val healthConnect = HealthConnectProvider(appContext)

    val dashboard: DashboardRepository = DefaultDashboardRepository(
        api = api,
        session = session,
        healthConnect = healthConnect,
        mock = MockHealthDataProvider(),
        settings = settings,
    )

    val auth = AuthRepository(
        api = api,
        session = session,
        socialClients = mapOf(
            LoginProvider.GOOGLE to GoogleSignInClient(appContext, BuildConfig.GOOGLE_SERVER_CLIENT_ID),
            LoginProvider.KAKAO to KakaoSignInClient(appContext, BuildConfig.KAKAO_NATIVE_APP_KEY),
            LoginProvider.NAVER to NaverSignInClient(
                appContext,
                BuildConfig.NAVER_CLIENT_ID,
                BuildConfig.NAVER_CLIENT_SECRET,
                BuildConfig.NAVER_CLIENT_NAME,
            ),
        ),
        scope = appScope,
    )

    /** 기기 지역(시스템 설정 기준, 앱 언어와 무관). 로그인 수단 노출 순서에 쓴다. */
    fun deviceRegion(): String? {
        // Android 13+는 앱별 언어(예: ko-KR)가 프로세스 기본 로케일을 바꾸므로 시스템 로케일을 따로 읽는다.
        val locales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.getSystemService(LocaleManager::class.java)?.systemLocales
        } else {
            null
        } ?: Resources.getSystem().configuration.locales
        return locales.takeUnless { it.isEmpty }?.get(0)?.country?.takeIf { it.isNotBlank() }
    }

    /** 앱 표시 언어 기준 `Accept-Language`(ko·en). 앱별 언어 설정(Android 13+)이 있으면 그것을 먼저 쓴다. */
    fun acceptLanguage(): String {
        val appLocales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.getSystemService(LocaleManager::class.java)?.applicationLocales
        } else {
            null
        }
        val locales = appLocales?.takeUnless { it.isEmpty } ?: LocaleList.getDefault()
        return AppLanguage.acceptLanguage((0 until locales.size()).map { locales[it].toLanguageTag() })
    }
}
