package com.lily.onpremise.expose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class HttpCloudflareClient implements CloudflareClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final String apiToken;
    private final String baseUrl;

    public HttpCloudflareClient(String apiToken) {
        this(apiToken, "https://api.cloudflare.com/client/v4");
    }

    public HttpCloudflareClient(String apiToken, String baseUrl) {
        this.apiToken = apiToken == null ? "" : apiToken;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public JsonNode call(String method, String path, JsonNode body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiToken)
                    .header("Content-Type", "application/json");
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.method(method, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.body() == null || response.body().isBlank()) {
                throw new IllegalStateException("cloudflare api " + response.statusCode());
            }
            JsonNode root = MAPPER.readTree(response.body());
            if (!root.path("success").asBoolean(false)) {
                String message = root.path("errors").path(0).path("message").asText("cloudflare api 실패");
                throw new IllegalStateException(message);
            }
            return root.get("result");
        } catch (IllegalStateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cloudflare api 가 중단되었습니다");
        } catch (Exception e) {
            throw new IllegalStateException("cloudflare api 호출 실패: " + e.getMessage(), e);
        }
    }
}
