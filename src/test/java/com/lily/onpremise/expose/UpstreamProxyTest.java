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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpstreamProxyTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void 포트_0으로_전환하면_앱을_뗀_것이라_503을_돌려주고_음수는_거절한다() throws Exception {
        UpstreamProxy proxy = new UpstreamProxy(0);
        proxy.start();
        HttpServer app = listen("one");
        try {
            proxy.switchTo(app.getAddress().getPort());
            assertThat(get(proxy.port()).body()).isEqualTo("one");
            proxy.switchTo(0);
            assertThat(get(proxy.port()).statusCode()).isEqualTo(503);
            assertThat(proxy.upstreamPort()).isZero();
            assertThatThrownBy(() -> proxy.switchTo(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            proxy.close();
            app.stop(0);
        }
    }

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

    @Test
    void 클라우드_비율만큼_로컬_여유와_상관없이_넘긴다() throws Exception {
        UpstreamProxy proxy = new UpstreamProxy(0);
        proxy.start();
        HttpServer local = listen("local");
        HttpServer cloud = listen("cloud");
        try {
            proxy.switchTo(local.getAddress().getPort());
            proxy.cloudShare(100);
            // 넘김 대상이 없으면 비율이 있어도 로컬이다
            assertThat(get(proxy.port()).body()).isEqualTo("local");

            proxy.overflowTo(cloud.getAddress());
            assertThat(get(proxy.port()).body()).isEqualTo("cloud");

            proxy.cloudShare(0);
            assertThat(get(proxy.port()).body()).isEqualTo("local");

            proxy.cloudShare(50);
            int toCloud = 0;
            for (int i = 0; i < 200; i++) {
                if ("cloud".equals(get(proxy.port()).body())) {
                    toCloud++;
                }
            }
            assertThat(toCloud).isBetween(60, 140);
            assertThat(proxy.pressure().overflowedTotal()).isEqualTo(1 + toCloud);
        } finally {
            proxy.close();
            local.stop(0);
            cloud.stop(0);
        }
    }

    @Test
    void 로컬_앱_응답만_최근_1분에_센다() throws Exception {
        UpstreamProxy proxy = new UpstreamProxy(0);
        proxy.start();
        HttpServer app = listen("no", 500);
        try {
            assertThat(get(proxy.port()).statusCode()).isEqualTo(503);
            proxy.pause(true);
            proxy.switchTo(app.getAddress().getPort());
            assertThat(get(proxy.port()).statusCode()).isEqualTo(503);
            proxy.pause(false);

            assertThat(proxy.localTraffic().requests()).isZero();

            assertThat(get(proxy.port()).statusCode()).isEqualTo(500);
            TrafficWindow.Sample failed = proxy.localTraffic();
            assertThat(failed.requests()).isEqualTo(1);
            assertThat(failed.errors()).isEqualTo(1);
            assertThat(failed.critical()).isFalse();
        } finally {
            proxy.close();
            app.stop(0);
        }
    }

    @Test
    void 점검_중에는_503_과_Retry_After_를_돌려준다() throws Exception {
        UpstreamProxy proxy = new UpstreamProxy(0);
        proxy.start();
        HttpServer local = listen("local");
        try {
            proxy.switchTo(local.getAddress().getPort());
            proxy.pause(true);
            HttpResponse<String> paused = get(proxy.port());
            assertThat(paused.statusCode()).isEqualTo(503);
            assertThat(paused.headers().firstValue("Retry-After")).contains("30");

            proxy.pause(false);
            assertThat(get(proxy.port()).body()).isEqualTo("local");
        } finally {
            proxy.close();
            local.stop(0);
        }
    }

    private HttpResponse<String> get(int port) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer listen(String word) throws IOException {
        return listen(word, 200);
    }

    private static HttpServer listen(String word, int status) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = word.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }
}
