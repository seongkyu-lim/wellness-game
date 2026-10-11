package com.example.wellnessgame.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wellnessgame.auth.AuthRepository
import com.example.wellnessgame.auth.social.SignInHost
import com.example.wellnessgame.auth.social.SocialSignInCancelled
import com.example.wellnessgame.data.network.ApiException
import com.example.wellnessgame.domain.LoginProvider
import com.example.wellnessgame.domain.LoginProviderPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface LoginError {
    /** 서버가 번역해 보낸 문구(아이디 중복, 비밀번호 오류 등). 없으면 일반 문구. */
    data class Server(val message: String?) : LoginError
    data class RateLimited(val retryAfterSeconds: Long?) : LoginError
    data object Network : LoginError
    data class Social(val provider: LoginProvider) : LoginError
}

data class LoginUiState(
    val policy: LoginProviderPolicy,
    val configured: Set<LoginProvider>,
    val username: String = "",
    val password: String = "",
    val showOtherProviders: Boolean = false,
    val isBusy: Boolean = false,
    val error: LoginError? = null,
) {
    /** iOS와 같은 최소 조건: 아이디 입력 + 비밀번호 8자 이상. 세부 규칙은 서버가 검증한다. */
    val canSubmitCredentials: Boolean
        get() = username.isNotBlank() && password.length >= MIN_PASSWORD_LENGTH && !isBusy

    companion object {
        const val MIN_PASSWORD_LENGTH = 8
    }
}

/** 로그인 화면 상태. 성공하면 세션이 바뀌고 화면 전환은 상위(내비게이션)가 맡는다. */
class LoginViewModel(
    private val auth: AuthRepository,
    regionCode: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(
        LoginUiState(
            policy = LoginProviderPolicy.forRegion(regionCode),
            configured = LoginProviderPolicy.SOCIAL_PROVIDERS.filter(auth::isConfigured).toSet(),
        ),
    )
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun toggleOtherProviders() = _state.update { it.copy(showOtherProviders = !it.showOtherProviders) }

    fun logIn() = submitCredentials { username, password -> auth.logIn(username, password) }

    fun signUp() = submitCredentials { username, password -> auth.signUp(username, password) }

    fun socialSignIn(provider: LoginProvider, host: SignInHost) {
        if (_state.value.isBusy || provider !in _state.value.configured) return
        run(onSocialFailure = provider) { auth.socialSignIn(provider, host) }
    }

    private fun submitCredentials(action: suspend (String, String) -> Unit) {
        val current = _state.value
        if (!current.canSubmitCredentials) return
        run(onSocialFailure = null) { action(current.username, current.password) }
    }

    private fun run(onSocialFailure: LoginProvider?, block: suspend () -> Unit) {
        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            val error: LoginError? = try {
                block()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocialSignInCancelled) {
                null
            } catch (e: ApiException) {
                when (e) {
                    is ApiException.Network -> LoginError.Network
                    is ApiException.RateLimited -> LoginError.RateLimited(e.retryAfterSeconds)
                    is ApiException.Unauthorized -> LoginError.Server(e.serverMessage)
                    is ApiException.Server -> LoginError.Server(e.serverMessage)
                    is ApiException.InvalidResponse -> LoginError.Server(null)
                }
            } catch (e: Exception) {
                onSocialFailure?.let { LoginError.Social(it) } ?: LoginError.Server(null)
            }
            _state.update { it.copy(isBusy = false, error = error, password = if (error == null) "" else it.password) }
        }
    }
}
