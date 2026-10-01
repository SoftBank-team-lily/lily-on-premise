package com.lily.onpremise.job;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeployJobTest {

    @Test
    void 비어_있는_값은_플랫폼_기본값으로_채운다() {
        DeployJob job = new DeployJob(
                null, "https://github.com/acme/blog", null, "  ", "blog", null, null, null, "  ", null).normalize();

        assertThat(job.id()).hasSize(8);
        assertThat(job.branch()).isEqualTo("main");
        assertThat(job.targetPort()).isEqualTo(8080);
        assertThat(job.token()).isNull();
        assertThat(job.dockerfile()).isNull();
        assertThat(job.env()).isEmpty();
    }

    @Test
    void 주소에_심은_인증_정보는_거절한다() {
        assertThatThrownBy(() -> new DeployJob(
                "job1", "https://user:token@github.com/acme/blog", "main", null, "blog", 8080,
                null, null, null, null).normalize())
                .hasMessageContaining("token");
    }

    @Test
    void 슬롯_환경변수는_요청이_덮어쓰지_못한다() {
        assertThatThrownBy(() -> new DeployJob(
                "job1", "https://github.com/acme/blog", "main", null, "blog", 8080,
                null, null, null, Map.of("APP_COLOR", "red")).normalize())
                .hasMessageContaining("APP_COLOR");
    }

    @Test
    void 잡_메시지를_정규화한다() {
        DeployJob job = JobMessages.parse("""
                {"type":"job","repoUrl":"https://github.com/acme/blog","appName":"blog",
                 "token":"ghp_abc","env":{"SPRING_PROFILES_ACTIVE":"local"}}
                """);

        assertThat(job.branch()).isEqualTo("main");
        assertThat(job.targetPort()).isEqualTo(8080);
        assertThat(job.token()).isEqualTo("ghp_abc");
        assertThat(job.env()).containsEntry("SPRING_PROFILES_ACTIVE", "local");
    }

    @Test
    void tcp_헬스와_마이그레이션을_받는다() {
        DeployJob job = new DeployJob(
                "job1", "https://github.com/acme/blog", "main", null, "blog", 8080,
                "tcp", null, null, Map.of(), "postgres", "/ready",
                Map.of("V1__init.sql", "select 1")).normalize();

        assertThat(job.healthPath()).isEqualTo("tcp");
        assertThat(job.canaryPath()).isEqualTo("/ready");
        assertThat(job.migrations()).containsEntry("V1__init.sql", "select 1");
    }

    @Test
    void 마이그레이션_파일명이_아니면_거절한다() {
        assertThatThrownBy(() -> new DeployJob(
                "job1", "https://github.com/acme/blog", "main", null, "blog", 8080,
                "/", null, null, Map.of(), null, null, Map.of("notes.sql", "select 1")).normalize())
                .hasMessageContaining("migrations");
    }

    @Test
    void 롤백_메시지를_읽는다() {
        JobMessages.Inbound inbound = JobMessages.read(
                "{\"type\":\"rollback\",\"app\":\"blog\",\"id\":\"r1234567\"}");

        assertThat(inbound.job()).isNull();
        assertThat(inbound.rollbackApp()).isEqualTo("blog");
        assertThat(inbound.id()).isEqualTo("r1234567");
    }

    @Test
    void 잡이_아닌_메시지는_거절한다() {
        assertThatThrownBy(() -> JobMessages.parse("{\"type\":\"hello\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
