package com.wellnessgame.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oauth")
public record OAuthProperties(
        Provider kakao,
        Provider naver,
        Provider google
) {
    public record Provider(
            String clientId,
            String clientSecret,
            String tokenUri,
            String profileUri
    ) {
        public boolean configured() {
            return clientId != null && !clientId.isBlank();
        }
    }
}
