package com.wellnessgame.api;

import com.wellnessgame.auth.ForbiddenException;
import com.wellnessgame.auth.UnauthorizedException;
import com.wellnessgame.error.BadRequestException;
import com.wellnessgame.error.ServiceUnavailableException;
import com.wellnessgame.error.TooManyRequestsException;
import com.wellnessgame.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * API 오류 응답. 사용자에게 보여 줄 의도로 던진 예외({@link BadRequestException}, {@link ServiceUnavailableException},
 * {@link UnauthorizedException}, {@link ForbiddenException}, {@link TooManyRequestsException})만 메시지를 그대로 내보낸다.
 * 그 밖의 예외는 라이브러리 내부 문구가 새지 않도록 일반 문구({@code error.generic})로 응답하고 상세는 서버 로그에만 남긴다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<Map<String, Object>> handleUnauthorized(UnauthorizedException exception) {
        ResponseEntity<Map<String, Object>> response = userFacing(HttpStatus.UNAUTHORIZED, exception);
        return ResponseEntity.status(response.getStatusCode())
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(response.getBody());
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<Map<String, Object>> handleForbidden(ForbiddenException exception) {
        return userFacing(HttpStatus.FORBIDDEN, exception);
    }

    /** 레이트 리밋 초과(#48): 429 + {@code Retry-After}(초). */
    @ExceptionHandler(TooManyRequestsException.class)
    ResponseEntity<Map<String, Object>> handleTooManyRequests(TooManyRequestsException exception) {
        ResponseEntity<Map<String, Object>> response = userFacing(HttpStatus.TOO_MANY_REQUESTS, exception);
        return ResponseEntity.status(response.getStatusCode())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(response.getBody());
    }

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<Map<String, Object>> handleBadRequest(BadRequestException exception) {
        return userFacing(HttpStatus.BAD_REQUEST, exception);
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    ResponseEntity<Map<String, Object>> handleServiceUnavailable(ServiceUnavailableException exception) {
        return userFacing(HttpStatus.SERVICE_UNAVAILABLE, exception);
    }

    /** 사용자용으로 만들지 않은 IllegalArgumentException: 상태 코드(400)는 유지하고 문구만 일반화한다. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException exception) {
        return generic(HttpStatus.BAD_REQUEST, exception);
    }

    /** 사용자용으로 만들지 않은 IllegalStateException: 상태 코드(503)는 유지하고 문구만 일반화한다. */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException exception) {
        return generic(HttpStatus.SERVICE_UNAVAILABLE, exception);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse(Messages.get("validation.invalid-request"));
        logFor(HttpStatus.BAD_REQUEST, exception.getClass().getSimpleName() + ": " + message, exception);
        return error(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 그 밖의 런타임 예외는 500 + 일반 문구. 단, 스프링 MVC 가 자체 상태 코드로 처리하는 예외
     * (잘못된 JSON 400, 경로 변수 타입 오류 400, {@code ResponseStatusException} 등)는 다시 던져 기본 처리에 맡긴다.
     */
    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException exception) {
        if (isHandledByFramework(exception)) {
            throw exception;
        }
        return generic(HttpStatus.INTERNAL_SERVER_ERROR, exception);
    }

    private static boolean isHandledByFramework(RuntimeException exception) {
        return exception instanceof ErrorResponse
                || exception instanceof HttpMessageConversionException
                || exception instanceof TypeMismatchException
                || AnnotatedElementUtils.hasAnnotation(exception.getClass(), ResponseStatus.class);
    }

    /** 사용자용 예외: 메시지를 그대로 응답한다. */
    private ResponseEntity<Map<String, Object>> userFacing(HttpStatus status, RuntimeException exception) {
        logFor(status, describe(exception), exception);
        return error(status, exception.getMessage());
    }

    /** 사용자용이 아닌 예외: 상태 코드는 유지하고 일반 문구로 응답한다. 상세는 로그에만 남긴다. */
    private ResponseEntity<Map<String, Object>> generic(HttpStatus status, Exception exception) {
        logFor(status, describe(exception), exception);
        return error(status, Messages.get("error.generic"));
    }

    /** 4xx 는 클라이언트 문제라 스택 없이 한 줄(warn), 5xx 는 스택과 함께 error 로 남긴다. */
    private static void logFor(HttpStatus status, String summary, Exception exception) {
        if (status.is5xxServerError()) {
            log.error("{} 응답: {}", status.value(), summary, exception);
        } else {
            log.warn("{} 응답: {}", status.value(), summary);
        }
    }

    private static String describe(Exception exception) {
        return exception.getClass().getSimpleName() + ": " + exception.getMessage();
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message
        ));
    }
}
