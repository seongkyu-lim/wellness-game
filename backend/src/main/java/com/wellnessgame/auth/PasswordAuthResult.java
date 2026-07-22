package com.wellnessgame.auth;

/**
 * 아이디/비밀번호 인증 결과. userIdentifier는 iOS 앱이 "password:{userIdentifier}" 형태의
 * userId로 조합해 다른 API 호출에 사용한다.
 */
public record PasswordAuthResult(String userIdentifier, String displayName) {
}
