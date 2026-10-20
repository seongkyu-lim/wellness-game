package com.wellnessgame.auth;

/**
 * 액세스 토큰 권한 범위(#76). JWT 의 {@code scope} 클레임으로 싣는다.
 * 클레임이 없는 토큰(기존·일반 로그인 토큰)은 {@link #FULL} 이다.
 * 리프레시 토큰 family 에도 저장한다(refresh_token.scope, {@link TokenScopeConverter}, #105).
 */
public enum TokenScope {
    /** 모든 API. scope 클레임을 싣지 않는다. */
    FULL(null, "full"),
    /** 조회 전용(Watch, 웹 대시보드). GET 조회 API 만 허용한다({@link AuthenticationInterceptor}). */
    READ("read", "read");

    private final String claimValue;
    private final String columnValue;

    TokenScope(String claimValue, String columnValue) {
        this.claimValue = claimValue;
        this.columnValue = columnValue;
    }

    String claimValue() {
        return claimValue;
    }

    /** refresh_token.scope 컬럼 값. 다른 서버 구현과 공유하므로 바꾸지 않는다. */
    String columnValue() {
        return columnValue;
    }

    /** 컬럼 값으로 범위를 찾는다. 모르는 값이면 예외(데이터 오류). */
    static TokenScope fromColumn(String value) {
        for (TokenScope scope : values()) {
            if (scope.columnValue.equals(value)) {
                return scope;
            }
        }
        throw new IllegalStateException("알 수 없는 refresh_token.scope 값: " + value);
    }

    /** 클레임 값으로 범위를 찾는다. 없으면 FULL, 모르는 값이면 null(거부). */
    static TokenScope fromClaim(Object claim) {
        if (claim == null) {
            return FULL;
        }
        return READ.claimValue.equals(claim) ? READ : null;
    }
}
