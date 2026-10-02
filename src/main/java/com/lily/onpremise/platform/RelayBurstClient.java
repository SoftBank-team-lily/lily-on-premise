package com.lily.onpremise.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lily.onpremise.burst.BurstClient;

/**
 * lily-builder 의 /api/burst 호출을 컨트롤 플레인 소켓으로 보낸다. 버스팅 토큰은 사용자 PC 에 오지 않는다.
 * builder 는 이 에이전트로 배포한 앱에 대한 호출만 통과시킨다.
 */
public final class RelayBurstClient extends BurstClient {

    private final PlatformChannel channel;

    public RelayBurstClient(PlatformChannel channel, ObjectMapper json) {
        super(json);
        this.channel = channel;
    }

    @Override
    protected JsonNode send(String method, String path, Object body) {
        ObjectNode request = PlatformChannel.request("burst");
        request.put("method", method);
        request.put("path", path);
        if (body != null) {
            request.set("body", json.valueToTree(body));
        }
        JsonNode result = channel.call(request);
        return result == null || result.isMissingNode() || result.isNull() ? json.createObjectNode() : result;
    }
}
