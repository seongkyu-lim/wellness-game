package com.wellnessgame.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** UTF-8 문자열의 SHA-256 소문자 hex. 리프레시 토큰 저장 해시와 Apple nonce 비교에 쓴다. */
final class Sha256 {
    private Sha256() {
    }

    static String hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다.", e); // 모든 JVM 이 지원해야 하는 알고리즘
        }
    }
}
