package com.wellnessgame.auth;

import com.wellnessgame.error.ServiceUnavailableException;
import com.wellnessgame.i18n.Messages;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.IncorrectClaimException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MissingClaimException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Date;

/**
 * iOS Sign in with Apple 의 identity token(JWT)을 Apple JWKS 로 직접 검증해 "apple:{sub}" userId 를 만든다.
 *
 * <ul>
 *   <li>서명: RS256 만 허용하고 헤더 kid 로 JWKS 의 공개키를 고른다({@link JwksKeyCache}: 캐시·장애 격리 정책).</li>
 *   <li>클레임: iss = https://appleid.apple.com, aud = oauth.apple.bundle-id, exp 필수·미래.</li>
 *   <li>nonce(#64): 요청에 nonce 원문이 있으면 토큰의 nonce 클레임이 SHA-256(원문) hex 와 같아야 한다.
 *       oauth.apple.require-nonce 가 true 면 nonce 없는 요청을 거부한다(재전송 방어).</li>
 *   <li>식별: sub 만 쓴다. 이메일(릴레이 주소일 수 있고 바뀔 수 있음)은 식별에 쓰지 않는다.</li>
 * </ul>
 */
@Service
public class AppleNativeAuthService {
    private static final Logger log = LoggerFactory.getLogger(AppleNativeAuthService.class);
    static final String APPLE_ISSUER = "https://appleid.apple.com";
    static final int MAX_FULL_NAME_LENGTH = 100;

    private final AppleNativeProperties properties;
    private final JwtParser parser;

    public AppleNativeAuthService(RestClient.Builder restClientBuilder, AppleNativeProperties properties, Clock clock) {
        this.properties = properties;
        this.parser = properties.configured() ? buildParser(restClientBuilder.build(), clock) : null;
    }

    /**
     * @param nonce iOS 가 Apple 요청에 SHA-256 해시로 넣은 nonce 의 원문. 없으면 null(구버전 앱).
     */
    public SocialLoginResult authenticate(String identityToken, String fullName, String nonce) {
        if (parser == null) {
            throw NativeAuthSupport.unavailable(log, "애플", "OAUTH_APPLE_BUNDLE_ID 환경변수", "auth.apple.unavailable");
        }
        NativeAuthSupport.requireToken(identityToken, "auth.apple.identity-token-blank");

        Claims claims = verify(identityToken.trim());
        String sub = claims.getSubject();
        if (sub == null || sub.isBlank() || claims.getExpiration() == null) {
            throw new UnauthorizedException(Messages.get("auth.apple.identity-token-invalid"));
        }
        verifyNonce(claims, nonce);
        return new SocialLoginResult("apple:" + sub, displayName(fullName));
    }

    private void verifyNonce(Claims claims, String nonce) {
        if (nonce == null || nonce.isEmpty()) {
            if (properties.requireNonce()) {
                throw new UnauthorizedException(Messages.get("auth.apple.nonce-required"));
            }
            return;
        }
        Object claim = claims.get("nonce");
        String expected = Sha256.hex(nonce);
        if (!(claim instanceof String actual) || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new UnauthorizedException(Messages.get("auth.apple.nonce-mismatch"));
        }
    }

    private Claims verify(String identityToken) {
        try {
            return parser.parseSignedClaims(identityToken).getPayload();
        } catch (JwksKeyCache.UnavailableException e) {
            throw new ServiceUnavailableException(Messages.get("auth.apple.verification-failed"), e.getCause());
        } catch (ExpiredJwtException e) {
            throw new UnauthorizedException(Messages.get("auth.apple.identity-token-expired"), e);
        } catch (IncorrectClaimException | MissingClaimException e) {
            String claim = e.getClaimName();
            String code = Claims.AUDIENCE.equals(claim) ? "auth.apple.wrong-audience"
                    : Claims.ISSUER.equals(claim) ? "auth.apple.wrong-issuer"
                    : "auth.apple.identity-token-invalid";
            throw new UnauthorizedException(Messages.get(code), e);
        } catch (JwtException | IllegalArgumentException e) {
            // 서명 불일치, 형식 오류, 지원하지 않는 alg, 모르는 kid 등
            throw new UnauthorizedException(Messages.get("auth.apple.identity-token-invalid"), e);
        }
    }

    private JwtParser buildParser(RestClient restClient, Clock clock) {
        JwksKeyCache keys = new JwksKeyCache("Apple", restClient, properties.jwksUri(), properties.jwksCacheTtl(),
                properties.jwksStaleMax(), clock);
        return Jwts.parser()
                .keyLocator(keys.locator())
                .requireIssuer(APPLE_ISSUER)
                .requireAudience(properties.bundleId())
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    private static String displayName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            // Apple 은 이름을 최초 로그인 때만 주므로 없을 때 기본 이름을 쓴다.
            return Messages.get("auth.apple.default-display-name");
        }
        String trimmed = fullName.trim();
        if (trimmed.codePointCount(0, trimmed.length()) <= MAX_FULL_NAME_LENGTH) {
            return trimmed;
        }
        // 거부 대신 잘라 쓴다. 서로게이트 쌍(이모지 등)이 깨지지 않게 코드 포인트 기준으로 자른다.
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX_FULL_NAME_LENGTH));
    }
}
