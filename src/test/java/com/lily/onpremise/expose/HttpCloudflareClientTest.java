package com.lily.onpremise.expose;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpCloudflareClientTest {

    @Test
    void 실패_메시지에_API_토큰을_넣지_않는다() throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = """
                    {"success":false,"errors":[{"message":"authentication failed"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            HttpCloudflareClient client = new HttpCloudflareClient(
                    "super-secret", "http://127.0.0.1:" + server.getAddress().getPort() + "/client/v4");
            assertThatThrownBy(() -> client.call("GET", "/accounts/acct/cfd_tunnel", null))
                    .hasMessageContaining("authentication failed")
                    .hasMessageNotContaining("super-secret");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 성공하면_result_만_돌려준다() throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = """
                    {"success":true,"result":{"id":"tid"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            HttpCloudflareClient client = new HttpCloudflareClient(
                    "super-secret", "http://127.0.0.1:" + server.getAddress().getPort() + "/client/v4");
            assertThat(client.call("GET", "/accounts/acct/cfd_tunnel/tid", null).path("id").asText()).isEqualTo("tid");
        } finally {
            server.stop(0);
        }
    }
}
