package com.lily.onpremise.expose;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamProxyTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void 전환_이후_요청만_새_포트로_간다() throws Exception {
        UpstreamProxy proxy = new UpstreamProxy(0);
        proxy.start();
        HttpServer first = listen("one");
        HttpServer second = listen("two");
        try {
            assertThat(get(proxy.port()).statusCode()).isEqualTo(503);
            proxy.switchTo(first.getAddress().getPort());
            assertThat(get(proxy.port()).body()).isEqualTo("one");
            proxy.switchTo(second.getAddress().getPort());
            assertThat(get(proxy.port()).body()).isEqualTo("two");
        } finally {
            proxy.close();
            first.stop(0);
            second.stop(0);
        }
    }

    private HttpResponse<String> get(int port) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer listen(String word) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = word.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }
}
