package com.example.wellnessgame.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.wellnessgame.WellnessApp
import com.example.wellnessgame.data.health.HealthDataException
import com.example.wellnessgame.data.network.ApiException
import java.util.concurrent.TimeUnit

/**
 * 1시간마다(네트워크 연결 시) 오늘 데이터를 동기화한다.
 * 로그인·권한이 없으면 조용히 건너뛰고, 일시적 실패(네트워크·429·5xx)만 재시도한다.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as WellnessApp).container
        container.awaitSessionRestored()
        if (!container.session.state.value.isSignedIn) return Result.success()
        val repository = container.dashboard
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

    companion object {
        private const val TAG = "SyncWorker"
        const val UNIQUE_NAME = "health-sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
