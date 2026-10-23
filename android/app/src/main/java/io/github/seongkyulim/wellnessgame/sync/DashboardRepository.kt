package com.example.wellnessgame.sync

import com.example.wellnessgame.auth.SessionManager
import com.example.wellnessgame.data.health.DailyHealthSnapshot
import com.example.wellnessgame.data.health.HealthAvailability
import com.example.wellnessgame.data.health.HealthDataProvider
import com.example.wellnessgame.data.network.ApiClient
import com.example.wellnessgame.data.network.ApiException
import com.example.wellnessgame.data.network.CharacterState
import com.example.wellnessgame.data.network.HealthActivitySyncRequest
import com.example.wellnessgame.data.network.HealthActivitySyncResponse
import com.example.wellnessgame.data.settings.AppSettings
import com.example.wellnessgame.domain.DailyQuestProgress
import com.example.wellnessgame.domain.fromActivities
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate

/** 건강 데이터 접근 상태. */
enum class HealthAccess { GRANTED, PERMISSION_REQUIRED, PROVIDER_UPDATE_REQUIRED, UNSUPPORTED }

data class SyncResult(val snapshot: DailyHealthSnapshot, val response: HealthActivitySyncResponse)

/** 홈 화면·백그라운드 동기화가 쓰는 데이터 경로. 뷰모델 테스트는 가짜 구현을 쓴다. */
interface DashboardRepository {
    suspend fun healthAccess(): HealthAccess

    /** 디버그용 Mock 데이터를 쓰는 중인지. */
    suspend fun isUsingMockData(): Boolean

    /** Health Connect 권한 요청 목록(백그라운드 읽기 지원 시 포함). */
    suspend fun permissionsToRequest(): Set<String>

    /** 로그인 없이 읽는 오늘 데이터(Mock 미리보기용). */
    suspend fun loadSnapshot(): DailyHealthSnapshot

    /** 오늘 데이터를 읽어 서버에 동기화한다. 로그인하지 않았으면 [ApiException.Unauthorized]. */
    suspend fun syncToday(): SyncResult

    /** 서버의 내 캐릭터. 첫 동기화 전이면 null. */
    suspend fun character(): CharacterState?

    /** 서버에 저장된 [date]의 기록으로 만든 퀘스트 현재값. 날짜는 동기화한 스냅샷의 날짜를 넘긴다(자정 경계). */
    suspend fun todayQuests(date: LocalDate): DailyQuestProgress
}

class DefaultDashboardRepository(
    private val api: ApiClient,
    private val session: SessionManager,
    private val healthConnect: HealthDataProvider,
    private val mock: HealthDataProvider,
    private val settings: AppSettings,
    private val clock: Clock = Clock.systemDefaultZone(),
) : DashboardRepository {

    override suspend fun isUsingMockData(): Boolean = settings.useMockData.first()

    private suspend fun provider(): HealthDataProvider = if (isUsingMockData()) mock else healthConnect

    override suspend fun healthAccess(): HealthAccess {
        val provider = provider()
        return when (provider.availability()) {
            HealthAvailability.AVAILABLE -> if (provider.hasPermissions()) HealthAccess.GRANTED else HealthAccess.PERMISSION_REQUIRED
            HealthAvailability.PROVIDER_UPDATE_REQUIRED -> HealthAccess.PROVIDER_UPDATE_REQUIRED
            HealthAvailability.UNSUPPORTED -> HealthAccess.UNSUPPORTED
        }
    }

    override suspend fun permissionsToRequest(): Set<String> = healthConnect.permissionsToRequest()

    override suspend fun loadSnapshot(): DailyHealthSnapshot = provider().loadToday()

    override suspend fun syncToday(): SyncResult {
        val userId = session.state.value.userId ?: throw ApiException.Unauthorized(null)
        val snapshot = provider().loadToday()
        val response = api.sync(
            HealthActivitySyncRequest(userId = userId, date = snapshot.date.toString(), activities = snapshot.activities),
        )
        settings.setLastSyncedAt(clock.instant())
        return SyncResult(snapshot, response)
    }

    override suspend fun character(): CharacterState? = api.myCharacter()?.character

    override suspend fun todayQuests(date: LocalDate): DailyQuestProgress =
        DailyQuestProgress.fromActivities(api.dailyActivities(date.toString()).activities)
}
