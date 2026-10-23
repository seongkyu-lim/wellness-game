package io.github.seongkyulim.wellnessgame.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.seongkyulim.wellnessgame.auth.SessionManager
import io.github.seongkyulim.wellnessgame.auth.SessionState
import io.github.seongkyulim.wellnessgame.data.health.DailyHealthSnapshot
import io.github.seongkyulim.wellnessgame.data.health.HealthDataException
import io.github.seongkyulim.wellnessgame.data.network.ActivityXpResult
import io.github.seongkyulim.wellnessgame.data.network.ApiException
import io.github.seongkyulim.wellnessgame.data.network.CharacterState
import io.github.seongkyulim.wellnessgame.data.settings.AppSettings
import io.github.seongkyulim.wellnessgame.domain.AutoSyncPolicy
import io.github.seongkyulim.wellnessgame.domain.DailyQuestProgress
import io.github.seongkyulim.wellnessgame.domain.fromSnapshot
import io.github.seongkyulim.wellnessgame.sync.DashboardRepository
import io.github.seongkyulim.wellnessgame.sync.HealthAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** 홈 하단 상태 문구. 화면이 문자열 리소스로 바꾼다. */
sealed interface HomeStatus {
    data object Idle : HomeStatus
    data object SignInRequired : HomeStatus
    data object MockPreview : HomeStatus
    data class Synced(val levelUp: Boolean) : HomeStatus
    data object HealthPermissionRequired : HomeStatus
    data object HealthUnavailable : HomeStatus
    data object HealthReadFailed : HomeStatus
    data object SessionExpired : HomeStatus
    data class RateLimited(val retryAfterSeconds: Long?) : HomeStatus
    data object NetworkError : HomeStatus
    data class ServerError(val message: String?) : HomeStatus
}

data class HomeUiState(
    val session: SessionState = SessionState(),
    val character: CharacterState? = null,
    val gainedXp: Int = 0,
    val levelUp: Boolean = false,
    val quests: DailyQuestProgress? = null,
    val snapshot: DailyHealthSnapshot? = null,
    val results: List<ActivityXpResult> = emptyList(),
    val healthAccess: HealthAccess? = null,
    val lastSyncedAt: Instant? = null,
    val useMockData: Boolean = false,
    val isLoading: Boolean = false,
    val status: HomeStatus = HomeStatus.Idle,
)

/**
 * 홈 화면 상태(iOS `DashboardViewModel`).
 * 동기화 시점: 앱 포그라운드 진입(최소 5분 간격), 로그인 직후, 새로고침(간격 무시), Mock 토글 변경.
 * 로그인하지 않았으면 서버에 보내지 않는다(Mock이면 기기 안에서 미리보기만).
 */
class HomeViewModel(
    private val repository: DashboardRepository,
    private val session: SessionManager,
    private val settings: AppSettings,
    /** 시각(최소 간격)과 로컬 날짜(서버 기록 조회)에 쓴다. */
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    /** 진행 중인 refresh가 시작될 때의 사용자. */
    private var refreshUserId: String? = null

    init {
        viewModelScope.launch {
            settings.lastSyncedAt.collect { at -> _state.update { it.copy(lastSyncedAt = at) } }
        }
        viewModelScope.launch {
            settings.useMockData.collect { mock -> _state.update { it.copy(useMockData = mock) } }
        }
        viewModelScope.launch {
            session.state
                .filter { it.restored }
                .distinctUntilChangedBy { it.userId }
                .collect { sessionState ->
                    val wasExpired = sessionState.reloginRequired
                    _state.update {
                        HomeUiState(
                            session = sessionState,
                            lastSyncedAt = it.lastSyncedAt,
                            useMockData = it.useMockData,
                            status = if (wasExpired) HomeStatus.SessionExpired else HomeStatus.Idle,
                        )
                    }
                    // 로그인 직후(또는 앱 시작 시 복원된 세션)에는 바로 동기화한다.
                    refresh(force = sessionState.isSignedIn)
                }
        }
    }

    /** 포그라운드 진입 시 호출. 최소 간격 안이면 건너뛴다. */
    fun onForeground() = refresh(force = false)

    /**
     * 같은 사용자의 비강제 refresh가 이미 돌고 있으면 건너뛴다.
     * 사용자가 바뀌었거나(로그인·로그아웃·계정 전환) 강제 동기화면 이전 작업을 취소하고 다시 시작한다.
     */
    fun refresh(force: Boolean = true) {
        val running = refreshJob?.isActive == true
        if (running && !force && refreshUserId == session.state.value.userId) return
        restartRefresh(force)
    }

    fun setUseMockData(enabled: Boolean) {
        viewModelScope.launch {
            settings.setUseMockData(enabled)
            restartRefresh(force = true)
        }
    }

    /** Health Connect 권한 요청 결과를 받은 뒤. */
    fun onPermissionsResult() = restartRefresh(force = true)

    private fun restartRefresh(force: Boolean) {
        refreshJob?.cancel()
        refreshUserId = session.state.value.userId
        refreshJob = viewModelScope.launch { performRefresh(force) }
    }

    suspend fun permissionsToRequest(): Set<String> = repository.permissionsToRequest()

    private suspend fun performRefresh(force: Boolean) {
        val current = session.state.value
        if (!current.restored) return
        if (!current.isSignedIn) {
            previewSignedOut(current)
            return
        }
        val lastSynced = settings.lastSyncedAt.first()
        if (!force && _state.value.character != null && !AutoSyncPolicy.shouldSync(lastSynced, clock.instant())) return

        val userId = current.userId
        _state.update { it.copy(isLoading = true) }
        var questDate = LocalDate.now(clock)
        try {
            val access = repository.healthAccess()
            _state.update { it.copy(healthAccess = access) }
            if (access == HealthAccess.GRANTED) {
                val result = repository.syncToday()
                if (session.state.value.userId != userId) return
                questDate = result.snapshot.date
                _state.update {
                    it.copy(
                        character = result.response.character,
                        gainedXp = result.response.gainedXp,
                        levelUp = result.response.levelUp,
                        snapshot = result.snapshot,
                        results = result.response.activityResults,
                        quests = DailyQuestProgress.fromSnapshot(result.snapshot),
                        status = HomeStatus.Synced(result.response.levelUp),
                    )
                }
            } else {
                val character = repository.character()
                if (session.state.value.userId != userId) return
                _state.update {
                    it.copy(
                        character = character,
                        status = if (access == HealthAccess.PERMISSION_REQUIRED) {
                            HomeStatus.HealthPermissionRequired
                        } else {
                            HomeStatus.HealthUnavailable
                        },
                    )
                }
            }
            // 다른 기기에서 보낸 기록까지 합친 서버 값을 우선한다. 실패해도 기기 값으로 보여 준다.
            val serverQuests = try {
                repository.todayQuests(questDate)
            } catch (e: ApiException) {
                if (e is ApiException.Unauthorized) throw e
                null
            }
            if (serverQuests != null && session.state.value.userId == userId) {
                _state.update { it.copy(quests = serverQuests) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // 요청 도중 계정이 바뀌었다면 늦게 도착한 오류를 새 화면에 표시하지 않는다.
            if (session.state.value.userId == userId) _state.update { it.copy(status = e.toStatus()) }
        } catch (e: HealthDataException) {
            _state.update {
                it.copy(
                    status = when (e.reason) {
                        HealthDataException.Reason.PERMISSION_DENIED -> HomeStatus.HealthPermissionRequired
                        HealthDataException.Reason.UNAVAILABLE -> HomeStatus.HealthUnavailable
                        HealthDataException.Reason.READ_FAILED -> HomeStatus.HealthReadFailed
                    },
                )
            }
        } finally {
            // 취소된 작업이 새 작업의 로딩 표시를 끄지 않게 한다.
            if (currentCoroutineContext().isActive) _state.update { it.copy(isLoading = false) }
        }
    }

    /** 로그인 전: 서버에는 보내지 않고, Mock이면 기기 안에서만 미리보기를 보여 준다. */
    private suspend fun previewSignedOut(current: SessionState) {
        val status = if (current.reloginRequired) HomeStatus.SessionExpired else HomeStatus.SignInRequired
        if (!settings.useMockData.first()) {
            _state.update { it.copy(snapshot = null, quests = null, status = status) }
            return
        }
        try {
            val snapshot = repository.loadSnapshot()
            _state.update {
                it.copy(
                    snapshot = snapshot,
                    quests = DailyQuestProgress.fromSnapshot(snapshot),
                    status = if (current.reloginRequired) status else HomeStatus.MockPreview,
                )
            }
        } catch (e: HealthDataException) {
            _state.update { it.copy(status = HomeStatus.HealthReadFailed) }
        }
    }

    private fun ApiException.toStatus(): HomeStatus = when (this) {
        is ApiException.Unauthorized -> HomeStatus.SessionExpired
        is ApiException.RateLimited -> HomeStatus.RateLimited(retryAfterSeconds)
        is ApiException.Network -> HomeStatus.NetworkError
        is ApiException.Server -> HomeStatus.ServerError(serverMessage)
        is ApiException.InvalidResponse -> HomeStatus.ServerError(null)
    }
}
