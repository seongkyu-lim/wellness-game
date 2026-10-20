package com.wellnessgame.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 문자열의 UTF-8 바이트 길이 상한. null 은 통과한다(@NotBlank 등과 함께 쓴다).
 * bcrypt 는 입력의 앞 72바이트만 쓰므로 비밀번호 길이를 글자 수가 아니라 바이트로 제한한다(#106).
 */
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxUtf8Bytes {
    int value();

    String message() default "{validation.max-utf8-bytes}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
