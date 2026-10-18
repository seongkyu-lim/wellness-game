package com.wellnessgame.schema;

import com.wellnessgame.activity.HealthActivity;
import com.wellnessgame.auth.RefreshToken;
import com.wellnessgame.auth.UserAuthState;
import com.wellnessgame.auth.UserCredential;
import com.wellnessgame.character.UserCharacter;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문자열 enum 컬럼의 check 제약(마이그레이션 SQL)과 enum 값이 같은지 확인한다. ddl-auto=validate 는 check 제약을 보지 않아,
 * enum 값을 추가하고 마이그레이션을 빠뜨리면 기동은 되지만 운영 INSERT 가 23514(check 위반)로 실패한다.
 */
class EnumCheckConstraintTest {
    private static final List<Class<?>> ENTITIES = List.of(
            HealthActivity.class, UserCharacter.class, UserCredential.class, RefreshToken.class, UserAuthState.class);
    private static final Pattern TABLE = Pattern.compile("(?:create|alter)\\s+table\\s+(?:if\\s+exists\\s+)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHECK_IN = Pattern.compile("check\\s*\\(\\s*(\\w+)\\s+in\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__");

    @Test
    void everyStringEnumColumnHasCheckConstraintListingExactlyItsValues() throws IOException {
        Map<String, Set<String>> checks = checkConstraintsFromMigrations();
        Map<String, Set<String>> enums = stringEnumColumns();

        assertThat(enums).as("문자열 enum 컬럼이 하나 이상 있어야 한다").isNotEmpty();
        enums.forEach((column, values) -> assertThat(checks.get(column))
                .as("%s 의 check 제약이 enum 값과 다르다. 새 마이그레이션으로 check 제약을 갱신하세요.", column)
                .isEqualTo(values));
    }

    /** 테이블.컬럼 → check 의 허용 값. 뒤 버전의 마이그레이션이 앞 버전을 덮는다. */
    private static Map<String, Set<String>> checkConstraintsFromMigrations() throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*.sql");
        assertThat(files).isNotEmpty();
        Map<String, Set<String>> checks = new HashMap<>();
        for (Resource file : Arrays.stream(files).sorted(Comparator.comparingInt(EnumCheckConstraintTest::version)).toList()) {
            String sql = new String(file.getInputStream().readAllBytes(), StandardCharsets.UTF_8).replaceAll("--[^\\n]*", "");
            for (String statement : sql.split(";")) {
                Matcher table = TABLE.matcher(statement);
                if (!table.find()) {
                    continue;
                }
                Matcher check = CHECK_IN.matcher(statement);
                while (check.find()) {
                    Set<String> values = Arrays.stream(check.group(2).split(","))
                            .map(value -> value.trim().replaceAll("^'|'$", ""))
                            .collect(Collectors.toSet());
                    checks.put(key(table.group(1), check.group(1)), values);
                }
            }
        }
        return checks;
    }

    private static int version(Resource file) {
        Matcher matcher = VERSION.matcher(file.getFilename());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }

    private static Map<String, Set<String>> stringEnumColumns() {
        Map<String, Set<String>> columns = new HashMap<>();
        for (Class<?> entity : ENTITIES) {
            String table = entity.getAnnotation(Table.class).name();
            for (Field field : entity.getDeclaredFields()) {
                Enumerated enumerated = field.getAnnotation(Enumerated.class);
                if (enumerated == null || enumerated.value() != EnumType.STRING) {
                    continue;
                }
                Column column = field.getAnnotation(Column.class);
                String name = column != null && !column.name().isEmpty() ? column.name() : snakeCase(field.getName());
                Set<String> values = Arrays.stream(field.getType().getEnumConstants())
                        .map(constant -> ((Enum<?>) constant).name())
                        .collect(Collectors.toSet());
                columns.put(key(table, name), values);
            }
        }
        return columns;
    }

    private static String snakeCase(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static String key(String table, String column) {
        return table.toLowerCase(Locale.ROOT) + "." + column.toLowerCase(Locale.ROOT);
    }
}
