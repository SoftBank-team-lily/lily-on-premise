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
