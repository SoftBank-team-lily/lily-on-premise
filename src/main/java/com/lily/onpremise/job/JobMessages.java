package com.lily.onpremise.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 컨트롤 플레인과 에이전트가 주고받는 잡 메시지.
 * 소켓이 WebSocket 인 이유와 별개로, 본문은 이 한 가지다.
 */
public final class JobMessages {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JobMessages() {
    }

    public static DeployJob parse(String json) {
        Inbound inbound = read(json);
        if (inbound.job() == null) {
            throw new IllegalArgumentException("지원하지 않는 메시지입니다");
        }
        return inbound.job();
    }

    public static Inbound read(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            String type = node.path("type").asText();
            if ("rollback".equals(type)) {
                String app = node.path("app").asText();
                if (!app.matches("[a-z][a-z0-9-]{0,30}")) {
                    throw new IllegalArgumentException("app 이 올바르지 않습니다");
                }
                String id = node.path("id").asText("");
                return new Inbound(null, app, id.isBlank() ? null : id, null, null, false);
            }
            if ("home".equals(type)) {
                String app = node.path("app").asText();
                if (!app.matches("[a-z][a-z0-9-]{0,30}")) {
                    throw new IllegalArgumentException("app 이 올바르지 않습니다");
                }
                String home = node.path("home").asText();
                if (!home.equals("cloud") && !home.equals("onprem")) {
                    throw new IllegalArgumentException("home 은 cloud 또는 onprem 입니다");
                }
                String id = node.path("id").asText("");
                return new Inbound(null, null, id.isBlank() ? null : id, app, home,
                        node.path("migrateDatabase").asBoolean(false));
            }
            if (!"job".equals(type)) {
                throw new IllegalArgumentException("지원하지 않는 메시지입니다");
            }
            return new Inbound(MAPPER.treeToValue(node, DeployJob.class).normalize(), null, null, null, null, false);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("잡 메시지를 읽지 못했습니다");
        }
    }

    /**
     * job, rollbackApp, homeApp 중 하나만 채워진다
     *
     * @param migrateDatabase 거점 전환 때 앱 DB 도 옮긴다 (클라우드로: 내 PC → RDS, 온프레미스로: RDS → 내 PC)
     */
    public record Inbound(DeployJob job, String rollbackApp, String id, String homeApp, String home,
                          boolean migrateDatabase) {
    }
}
