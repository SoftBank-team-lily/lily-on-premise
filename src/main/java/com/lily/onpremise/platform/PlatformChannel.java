package com.lily.onpremise.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 컨트롤 플레인 소켓 위의 요청·응답. 요청에 rid 를 붙여 보내고, 같은 rid 의 응답을 기다린다.
 *
 * <p>응답은 소켓 수신 스레드가 {@link #complete} 로 넘긴다. 그 스레드에서 {@link #call} 을 부르면 응답을 받을 수 없으니
 * 수신 스레드 밖에서만 부른다.
 */
@Component
public class PlatformChannel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_SECONDS = 30;

    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private volatile Consumer<String> sender;

    /** 소켓이 열리면 보낼 곳을 넘기고, 닫히면 null 을 넘긴다 */
    public void attach(Consumer<String> sender) {
        this.sender = sender;
        if (sender == null) {
            pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException("컨트롤 플레인 연결이 끊겼습니다")));
            pending.clear();
        }
    }

    /**
     * @param request type 과 본문. rid 는 여기서 붙인다
     * @return 응답의 result
     * @throws IllegalStateException 연결이 없거나, 거절되었거나, 제한 시간 안에 답이 없다
     */
    public JsonNode call(ObjectNode request) {
        Consumer<String> out = sender;
        if (out == null) {
            throw new IllegalStateException("컨트롤 플레인에 연결돼 있지 않습니다");
        }
        String rid = UUID.randomUUID().toString().substring(0, 12);
        request.put("rid", rid);
        CompletableFuture<JsonNode> answer = new CompletableFuture<>();
        pending.put(rid, answer);
        try {
            out.accept(MAPPER.writeValueAsString(request));
            JsonNode reply = answer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!reply.path("ok").asBoolean(false)) {
                throw new IllegalStateException(reply.path("message").asText("컨트롤 플레인이 거절했습니다"));
            }
            return reply.path("result");
        } catch (TimeoutException e) {
            throw new IllegalStateException("컨트롤 플레인 응답이 " + TIMEOUT_SECONDS + "초 안에 오지 않았습니다");
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause() == null ? "요청 실패" : e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("요청을 만들지 못했습니다");
        } finally {
            pending.remove(rid);
        }
    }

    /** @return 이 채널이 기다리던 응답이면 true */
    public boolean complete(JsonNode reply) {
        CompletableFuture<JsonNode> waiter = pending.get(reply.path("rid").asText(""));
        if (waiter == null) {
            return false;
        }
        waiter.complete(reply);
        return true;
    }

    static ObjectNode request(String type) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", type);
        return node;
    }
}
