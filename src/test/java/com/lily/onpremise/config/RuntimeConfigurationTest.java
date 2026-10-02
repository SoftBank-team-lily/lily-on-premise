package com.lily.onpremise.config;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeConfigurationTest {

    @Test
    void 루프백_헬스와_판정은_h2c_업그레이드_없이_HTTP_1_1_로_보낸다() {
        assertThat(RuntimeConfiguration.loopbackHttp().version()).isEqualTo(HttpClient.Version.HTTP_1_1);
    }
}
