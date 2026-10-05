package com.wellnessgame.auth;

/**
 * 요청에 실린 userId(body/경로/쿼리)를 토큰 주체와 대조한다.
 */
public final class UserAccess {
    private UserAccess() {
    }

    /**
     * 요청 userId 가 없으면(null·공백) 토큰 주체를, 있으면 주체와 같을 때만 그 값을 돌려준다.
     *
     * @throws ForbiddenException 요청 userId 가 토큰 주체와 다를 때
     */
    public static String resolve(String authenticatedUserId, String requestedUserId) {
        if (requestedUserId == null || requestedUserId.isBlank()) {
            return authenticatedUserId;
        }
        if (!requestedUserId.equals(authenticatedUserId)) {
            throw new ForbiddenException("다른 사용자의 데이터입니다.");
        }
        return authenticatedUserId;
    }
}
