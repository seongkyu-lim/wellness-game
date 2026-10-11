package com.example.wellnessgame.sync

import android.util.Log
import androidx.work.ListenableWorker.Result
import com.example.wellnessgame.data.health.HealthDataException
import com.example.wellnessgame.data.network.ApiException

/** [SyncWorker]가 Application에서 동기화 작업을 얻는 경로. 테스트 Application이 가짜를 넘긴다. */
interface BackgroundSyncProvider {
    fun backgroundSync(): BackgroundSync
}

/**
 * 백그라운드 1회 동기화와 결과 분기.
 * 로그인·권한이 없거나 Mock 데이터 사용 중이면 조용히 건너뛰고(가짜 데이터를 서버에 보내지 않음),
 * 일시적 실패(네트워크·429·5xx)만 재시도한다.
 */
class BackgroundSync(
    private val awaitSessionRestored: suspend () -> Unit,
    private val isSignedIn: () -> Boolean,
    private val repository: DashboardRepository,
) {
    suspend fun run(): Result {
        awaitSessionRestored()
        if (!isSignedIn()) return Result.success()
        if (repository.isUsingMockData()) return Result.success()
        if (repository.healthAccess() != HealthAccess.GRANTED) return Result.success()
        return try {
            repository.syncToday()
            Result.success()
        } catch (e: ApiException) {
            when (e) {
                is ApiException.Network, is ApiException.RateLimited -> Result.retry()
                is ApiException.Server -> if (e.status >= 500) Result.retry() else Result.success()
                // 401은 ApiClient가 세션을 지웠다. 다음 실행은 로그인 전까지 건너뛴다.
                is ApiException.Unauthorized, is ApiException.InvalidResponse -> Result.success()
            }
        } catch (e: HealthDataException) {
            // 백그라운드 읽기 권한이 없는 기기 등: 포그라운드 동기화에 맡긴다.
            Log.i(TAG, "Background health read skipped: ${e.reason}")
            Result.success()
        }
    }

    private companion object {
        const val TAG = "BackgroundSync"
    }
}
