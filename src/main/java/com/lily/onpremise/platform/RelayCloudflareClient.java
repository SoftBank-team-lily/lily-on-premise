package com.lily.onpremise.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lily.onpremise.expose.CloudflareClient;

/**
 * Cloudflare API 를 컨트롤 플레인(lily-builder)이 대신 부른다. API 토큰은 사용자 PC 에 오지 않는다.
 * builder 는 이 에이전트의 터널과 이 에이전트로 배포한 앱의 호스트이름에 대한 호출만 통과시킨다.
 */
public final class RelayCloudflareClient implements CloudflareClient {

    private final PlatformChannel channel;

    public RelayCloudflareClient(PlatformChannel channel) {
        this.channel = channel;
    }

    @Override
    public JsonNode call(String method, String path, JsonNode body) {
        ObjectNode request = PlatformChannel.request("cloudflare");
        request.put("method", method);
        request.put("path", path);
        if (body != null) {
            request.set("body", body);
        }
        JsonNode result = channel.call(request);
        return result == null || result.isMissingNode() ? null : result;
    }
}
