package com.wellnessgame.i18n;

import com.wellnessgame.activity.WorkoutType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MessagesPropertiesTest {
    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void koreanAndEnglishBundlesHaveSameKeys() throws IOException {
        Set<String> ko = load("messages.properties").stringPropertyNames();
        Set<String> en = load("messages_en.properties").stringPropertyNames();

        assertThat(ko).isNotEmpty();
        assertThat(en).containsExactlyInAnyOrderElementsOf(ko);
    }

    @Test
    void everyWorkoutTypeHasDisplayName() throws IOException {
        Properties ko = load("messages.properties");
        Arrays.stream(WorkoutType.values())
                .forEach(type -> assertThat(ko).containsKey("workout.name." + type.name()));
    }

    @Test
    void defaultsToKoreanOutsideRequestRegardlessOfJvmLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            assertThat(Messages.get("auth.forbidden")).isEqualTo("다른 사용자의 데이터입니다.");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void resolvesEnglishAndFallsBackToKoreanForUnsupportedLocale() {
        LocaleContextHolder.setLocale(Locale.US);
        assertThat(Messages.get("sync.reason.steps-range", 100_000)).isEqualTo("Steps must be between 0 and 100,000.");
        assertThat(Messages.get("sync.error.date-out-of-range", 1))
                .isEqualTo("The sync date must be within 1 day of today.");

        LocaleContextHolder.setLocale(Locale.FRENCH);
        assertThat(Messages.get("sync.reason.steps-range", 100_000)).isEqualTo("걸음 수는 0 이상 100000 이하여야 합니다.");
    }

    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = MessagesPropertiesTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as(name).isNotNull();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
