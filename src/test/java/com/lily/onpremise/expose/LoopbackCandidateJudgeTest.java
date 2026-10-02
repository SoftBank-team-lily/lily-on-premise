package com.lily.onpremise.expose;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 후보 루프백만 보고, 5xx 가 기준을 넘으면 전환 전에 거절한다 */
class LoopbackCandidateJudgeTest {

    @Test
    void 후보가_5xx_면_거절한다() throws Exception {
        HttpServer candidate = server(500);
        HttpServer active = server(200);
        candidate.start();
        active.start();
        try {
            LoopbackCandidateJudge judge = judge();
            String reason = judge.reject(candidate.getAddress().getPort(), active.getAddress().getPort(), "/health")
                    .orElseThrow();
            assertThat(reason).contains("error rate");
        } finally {
            candidate.stop(0);
            active.stop(0);
        }
    }

    @Test
    void 둘_다_정상이면_통과한다() throws Exception {
        HttpServer candidate = server(200);
        HttpServer active = server(200);
        candidate.start();
        active.start();
        try {
            LoopbackCandidateJudge judge = judge();
            assertThat(judge.reject(candidate.getAddress().getPort(), active.getAddress().getPort(), "/health"))
                    .isEmpty();
        } finally {
            candidate.stop(0);
            active.stop(0);
        }
    }

    @Test
    void 후보가_뜨기_전의_연결_실패는_세지_않는다() throws Exception {
        // 포트는 열려 있지만 아직 응답하지 않는다 (Docker 포트 프록시가 앱보다 먼저 연결을 받는 상태)
        HttpServer candidate = server(200);
        HttpServer active = server(200);
        active.start();
        Thread late = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            candidate.start();
        });
        try {
            LoopbackCandidateJudge judge = new LoopbackCandidateJudge(
                    HttpClient.newHttpClient(), Duration.ofMillis(2000), 20, 300, 2, 0.05, 1000, 2.0, 100);
            assertThat(judge.reject(candidate.getAddress().getPort(), active.getAddress().getPort(), "/health"))
                    .isEmpty();
        } finally {
            late.join();
            candidate.stop(0);
            active.stop(0);
        }
    }

    @Test
    void 후보가_끝까지_응답하지_않으면_거절한다() throws Exception {
        HttpServer probe = server(200);
        int port = probe.getAddress().getPort();
        probe.stop(0);
        HttpServer active = server(200);
        active.start();
        try {
            assertThat(judge().reject(port, active.getAddress().getPort(), "/health").orElseThrow())
                    .contains("too few responses");
        } finally {
            active.stop(0);
        }
    }

    private static LoopbackCandidateJudge judge() {
        return new LoopbackCandidateJudge(
                HttpClient.newHttpClient(), Duration.ofMillis(400), 1, 500,
                2, 0.05, 1000, 2.0, 100);
    }

    private static HttpServer server(int status) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        return server;
    }
}
