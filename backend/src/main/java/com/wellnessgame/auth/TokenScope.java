package com.wellnessgame.auth;

/**
 * 액세스 토큰 권한 범위(#76). JWT 의 {@code scope} 클레임으로 싣는다.
 * 클레임이 없는 토큰(기존·일반 로그인 토큰)은 {@link #FULL} 이다.
 */
public enum TokenScope {
    /** 모든 API. scope 클레임을 싣지 않는다. */
    FULL(null),
    /** 조회 전용(Watch). GET 조회 API 만 허용한다({@link AuthenticationInterceptor}). */
    READ("read");

    private final String claimValue;

    TokenScope(String claimValue) {
        this.claimValue = claimValue;
    }

    String claimValue() {
        return claimValue;
    }

    /** 클레임 값으로 범위를 찾는다. 없으면 FULL, 모르는 값이면 null(거부). */
    static TokenScope fromClaim(Object claim) {
        if (claim == null) {
            return FULL;
        }
        return READ.claimValue.equals(claim) ? READ : null;
    }
}
