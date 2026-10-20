package com.wellnessgame.auth;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** {@link TokenScope} ↔ refresh_token.scope('full'·'read', V5 check 제약). enum 이름 대신 소문자 값을 쓴다. */
@Converter
public class TokenScopeConverter implements AttributeConverter<TokenScope, String> {
    @Override
    public String convertToDatabaseColumn(TokenScope scope) {
        return (scope == null ? TokenScope.FULL : scope).columnValue();
    }

    @Override
    public TokenScope convertToEntityAttribute(String value) {
        return value == null ? TokenScope.FULL : TokenScope.fromColumn(value);
    }
}
