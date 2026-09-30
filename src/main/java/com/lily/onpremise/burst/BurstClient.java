package com.lily.onpremise.burst;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lily.onpremise.config.AgentProperties;
import com.lily.onpremise.job.DeployJob;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** lily-builder 의 /api/burst 호출. 토큰은 로그에 남기지 않는다 */
public class BurstClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final String baseUrl;
    private final String token;

    public BurstClient(AgentProperties.Burst settings, ObjectMapper json) {
        this.json = json;
        this.baseUrl = settings.builderUrl().replaceAll("/+$", "");
        this.token = settings.token();
    }

    /** 클라우드에 같은 레포를 빌드·배포하고 레플리카 0 으로 대기시킨다. 빌드 id 를 돌려준다 */
    public String standby(DeployJob job, String host, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("repoUrl", job.repoUrl());
        body.put("branch", job.branch());
        body.put("token", job.token());
        body.put("rootDir", job.rootDir());
        body.put("appName", job.appName());
        body.put("targetPort", job.targetPort());
        body.put("database", database == null || database.isBlank() ? null : database);
        body.put("readinessPath", job.healthPath());
        body.put("livenessPath", job.healthPath());
        body.put("env", job.env());
        body.put("host", host);
        JsonNode node = send("POST", "/api/burst/apps/" + job.appName() + "/standby", body);
        return node.path("id").asText();
    }

    /** QUEUED / BUILDING / DEPLOYING / SUCCEEDED / FAILED. 마지막 로그를 같이 */
    public BuildState build(String id) {
        JsonNode node = send("GET", "/api/burst/builds/" + id, null);
        JsonNode logs = node.path("logs");
        String last = logs.isArray() && !logs.isEmpty() ? logs.get(logs.size() - 1).asText() : "";
        return new BuildState(node.path("status").asText(), last);
    }

    public AppState app(String appName) {
        JsonNode node = send("GET", "/api/burst/apps/" + appName, null);
        return new AppState(node.path("replicas").asInt(), node.path("readyReplicas").asInt());
    }

    /** 클라우드와 같은 DB 의 접속 정보를 host:port(터널) 기준으로 받는다 */
    public Map<String, String> database(String appName, String engine, String host, int port) {
        JsonNode node = send("POST", "/api/burst/apps/" + appName + "/database",
                Map.of("engine", engine, "host", host, "port", port));
        Map<String, String> env = new LinkedHashMap<>();
        node.path("env").fields().forEachRemaining(e -> env.put(e.getKey(), e.getValue().asText()));
        return env;
    }

    public AppState scale(String appName, int replicas) {
        JsonNode node = send("PUT", "/api/burst/apps/" + appName + "/replicas", Map.of("replicas", replicas));
        return new AppState(node.path("replicas").asInt(), node.path("readyReplicas").asInt());
    }

    private JsonNode send(String method, String path, Object body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json");
            request.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(method + " " + path + " -> " + response.statusCode());
            }
            return response.body() == null || response.body().isBlank()
                    ? json.createObjectNode()
                    : json.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    public record BuildState(String status, String lastLog) {
    }

    public record AppState(int replicas, int readyReplicas) {
    }
}
