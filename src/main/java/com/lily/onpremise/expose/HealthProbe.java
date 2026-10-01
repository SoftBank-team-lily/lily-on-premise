package com.lily.onpremise.expose;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class HealthProbe implements Readiness {

    private final HttpClient http;
    private final Duration timeout;
    private final Duration interval;

    public HealthProbe(HttpClient http, Duration timeout, Duration interval) {
        this.http = http;
        this.timeout = timeout;
        this.interval = interval;
    }

    /**
     * 헬스 경로 대신 이 값이 오면 포트가 열렸는지만 본다 (lily-builder 가 헬스 경로를 모르는 앱에 보낸다).
     * Spring Security 앱은 / 가 401·403 이라 HTTP 로 보면 뜨고도 실패한다
     */
    static final String TCP = "tcp";

    @Override
    public void await(int port, String path) {
        if (TCP.equalsIgnoreCase(path == null ? "" : path.trim())) {
            awaitPort(port);
            return;
        }
        URI uri = URI.create("http://127.0.0.1:" + port + path);
        long deadline = System.nanoTime() + timeout.toNanos();
        String last = "no response";
        while (true) {
            try {
                HttpResponse<Void> response = http.send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return;
                }
                last = "status " + response.statusCode();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("health interrupted");
            } catch (Exception e) {
                last = e.getMessage();
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("ready timeout: " + uri + " (" + last + ")");
            }
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("health interrupted");
            }
        }
    }

    private void awaitPort(int port) {
        long deadline = System.nanoTime() + timeout.toNanos();
        String last = "no response";
        while (true) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                return;
            } catch (IOException e) {
                last = e.getMessage();
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("ready timeout: tcp 127.0.0.1:" + port + " (" + last + ")");
            }
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("health interrupted");
            }
        }
    }
}
