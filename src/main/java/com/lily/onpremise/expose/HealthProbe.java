package com.lily.onpremise.expose;

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

    @Override
    public void await(int port, String path) {
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
}
