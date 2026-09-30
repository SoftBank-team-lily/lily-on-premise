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
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!"job".equals(node.path("type").asText())) {
                throw new IllegalArgumentException("지원하지 않는 메시지입니다");
            }
            return MAPPER.treeToValue(node, DeployJob.class).normalize();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("잡 메시지를 읽지 못했습니다");
        }
    }
}
