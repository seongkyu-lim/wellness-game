package com.wellnessgame.api;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 배포 플랫폼(Render healthCheckPath) 용 헬스체크. 프로세스가 요청을 받을 수 있는지만 본다.
 *
 * <p>DB 를 확인하지 않는 이유: DB 가 잠시 끊겨도 앱 인스턴스를 재시작해서 나아지지 않고, 연결 풀이 스스로 복구한다.
 * DB 를 포함하면 DB 장애 때 플랫폼이 멀쩡한 인스턴스를 계속 재시작한다. 기동 시 Flyway·JPA 검증이 실패하면
 * 애플리케이션이 뜨지 않으므로 첫 배포의 DB 연결 문제는 이 경로가 응답하지 않는 것으로 드러난다.
 *
 * <p>{@code /api} 밖 경로라 인증 인터셉터({@code /api/**})와 레이트 리밋({@code /api/auth/**}, 동기화 경로) 대상이 아니다.
 */
@RestController
public class HealthCheckController {
    private static final Map<String, String> UP = Map.of("status", "UP");

    @GetMapping("/healthz")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(UP);
    }
}
