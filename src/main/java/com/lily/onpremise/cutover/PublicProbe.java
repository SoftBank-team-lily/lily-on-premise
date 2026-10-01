package com.lily.onpremise.cutover;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 공개 주소를 에이전트 바깥에서 연다. 5xx 와 연결 실패는 실패다 */
final class PublicProbe {

    private PublicProbe() {
    }

    static boolean ok(String url) {
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            int code = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return code < 500;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
