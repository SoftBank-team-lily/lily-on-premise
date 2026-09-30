package com.lily.onpremise.expose;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HealthProbeTest {

    @Test
    void 이백이_오면_통과한다() throws Exception {
        HttpServer server = server(200);
        try {
            probe(Duration.ofMillis(500)).await(server.getAddress().getPort(), "/actuator/health/readiness");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 기한_안에_준비되지_않으면_실패한다() throws Exception {
        HttpServer server = server(503);
        try {
            assertThatThrownBy(() -> probe(Duration.ofMillis(200))
                    .await(server.getAddress().getPort(), "/actuator/health/readiness"))
                    .hasMessageContaining("ready timeout");
        } finally {
            server.stop(0);
        }
    }

    private static HealthProbe probe(Duration timeout) {
        return new HealthProbe(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build(),
                timeout,
                Duration.ofMillis(50));
    }

    private static HttpServer server(int status) throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
        server.createContext("/actuator/health/readiness", exchange -> {
            byte[] body = "UP".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        return server;
    }
}
