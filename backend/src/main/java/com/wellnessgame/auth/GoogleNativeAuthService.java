package com.wellnessgame.auth;

import com.wellnessgame.error.ServiceUnavailableException;
import com.wellnessgame.i18n.Messages;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * iOS Google Sign-In 이 발급한 ID 토큰을 Google JWKS 로 직접 검증해(#55, tokeninfo 호출 없음)
 * 웹 코드 교환({@link SocialAuthService})과 같은 "google:{sub}" userId 를 만든다.
 *
 * <ul>
 *   <li>서명: RS256 만 허용하고 헤더 kid 로 JWKS 의 공개키를 고른다({@link JwksKeyCache}: 캐시·장애 격리 정책).</li>
 *   <li>클레임: iss ∈ {accounts.google.com, https://accounts.google.com}, aud ∈ 허용 클라이언트 ID,
 *       azp(있으면) ∈ 허용 클라이언트 ID, exp 필수·미래, sub 필수.</li>
 *   <li>허용 클라이언트 ID 는 {@code oauth.google-native.client-ids} 에 명시한 값뿐이다. 웹 로그인 클라이언트 ID
 *       ({@code oauth.google.client-id})는 넣지 않는다: 웹 출처의 XSS 로 받은 웹용 ID 토큰이 이 경로로 전체 권한 세션을
 *       얻지 못하게 한다(웹 로그인은 조회 전용, #105). Android 는 웹 로그인과 다른 전용 웹 유형 클라이언트를 쓴다.</li>
 * </ul>
 */
@Service
public class GoogleNativeAuthService {
    private static final Logger log = LoggerFactory.getLogger(GoogleNativeAuthService.class);
    static final Set<String> GOOGLE_ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private final Set<String> allowedAudiences;
    private final Clock clock;
    private final JwtParser parser;

    public GoogleNativeAuthService(
            RestClient.Builder restClientBuilder,
            GoogleNativeProperties nativeProperties,
            Clock clock
    ) {
        this.allowedAudiences = allowedAudiences(nativeProperties);
        this.clock = clock;
        JwksKeyCache keys = new JwksKeyCache("Google", restClientBuilder.build(),
                nativeProperties.jwksUri(), nativeProperties.jwksCacheTtl(), nativeProperties.jwksStaleMax(), clock);
        this.parser = Jwts.parser()
                .keyLocator(keys.locator())
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    public SocialLoginResult authenticate(String idToken) {
        if (allowedAudiences.isEmpty()) {
            throw NativeAuthSupport.unavailable(log, "구글",
                    "OAUTH_GOOGLE_NATIVE_CLIENT_IDS(또는 OAUTH_GOOGLE_IOS_CLIENT_ID) 환경변수", "auth.google.unavailable");
        }
        NativeAuthSupport.requireToken(idToken, "auth.google.id-token-blank");

        Claims claims = verify(idToken.trim());
        Set<String> audience = claims.getAudience();
        if (audience == null || audience.isEmpty() || !allowedAudiences.containsAll(audience)) {
            throw new UnauthorizedException(Messages.get("auth.google.wrong-audience"));
        }
        Object azp = claims.get("azp");
        if (azp != null && !allowedAudiences.contains(azp.toString())) {
            throw new UnauthorizedException(Messages.get("auth.google.wrong-audience"));
        }
        if (!GOOGLE_ISSUERS.contains(claims.getIssuer())) {
            throw new UnauthorizedException(Messages.get("auth.google.wrong-issuer"));
        }
        Date exp = claims.getExpiration();
        if (exp == null) {
            throw new UnauthorizedException(Messages.get("auth.google.id-token-invalid"));
        }
        if (!exp.toInstant().isAfter(clock.instant())) {
            throw new UnauthorizedException(Messages.get("auth.google.id-token-expired"));
        }
        String sub = claims.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new UnauthorizedException(Messages.get("auth.google.id-token-invalid"));
        }
        Object name = claims.get("name");
        // iOS: GIDGoogleUser.userID == OIDC sub (SocialAuthService 의 웹 규칙과 동일)
        return new SocialLoginResult("google:" + sub, name instanceof String value ? value : null);
    }

    private Claims verify(String idToken) {
        try {
            return parser.parseSignedClaims(idToken).getPayload();
        } catch (JwksKeyCache.UnavailableException e) {
            throw new ServiceUnavailableException(Messages.get("auth.google.verification-failed"), e.getCause());
        } catch (ExpiredJwtException e) {
            throw new UnauthorizedException(Messages.get("auth.google.id-token-expired"), e);
        } catch (JwtException | IllegalArgumentException e) {
            // 서명 불일치, 형식 오류, 지원하지 않는 alg, 모르는 kid 등
            throw new UnauthorizedException(Messages.get("auth.google.id-token-invalid"), e);
        }
    }

    private static Set<String> allowedAudiences(GoogleNativeProperties nativeProperties) {
        Set<String> audiences = new LinkedHashSet<>();
        nativeProperties.clientIds().stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .forEach(audiences::add);
        return Set.copyOf(audiences);
    }
}
