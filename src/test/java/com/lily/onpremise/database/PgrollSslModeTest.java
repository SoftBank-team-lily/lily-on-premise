package com.lily.onpremise.database;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PgrollSslModeTest {

    @Test
    void 내_PC_DB는_disable_RDS는_require_사용자_DB는_주소의_sslmode를_따른다() {
        assertThat(DatabaseModes.sslmode("local", Map.of())).isEqualTo("disable");
        assertThat(DatabaseModes.sslmode("cloud", Map.of())).isEqualTo("require");
        assertThat(DatabaseModes.sslmode("external", Map.of("DB_URL", "jdbc:postgresql://h:5432/db")))
                .isEqualTo("disable");
        assertThat(DatabaseModes.sslmode("external", Map.of("DB_URL", "jdbc:postgresql://h:5432/db?sslmode=prefer")))
                .isEqualTo("require");
        assertThat(DatabaseModes.sslmode("external", Map.of("DB_URL", "jdbc:postgresql://h/db?a=1&sslmode=verify-full")))
                .isEqualTo("verify-full");
    }
}
