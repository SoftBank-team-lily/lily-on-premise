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
 *
 * <p>소켓은 잠깐씩 끊긴다. 어느 앱이든 배포해서 Ingress 가 바뀌면 ingress-nginx 가 리로드하고, 이전 워커가
 * worker-shutdown-timeout(240초) 뒤에 열린 WebSocket 을 닫는다. 세션은 5초 뒤 다시 붙으므로
 * {@link #call} 은 연결이 없으면 바로 실패하지 않고 다시 붙을 때까지 기다렸다가 보낸다.
 */
@Component
public class PlatformChannel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_SECONDS = 30;
    /** 다시 붙기를 기다리는 시간. 세션은 5초마다 다시 붙는다 */
    static final long RECONNECT_WAIT_MILLIS = 30_000;

    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Object connection = new Object();
    private final long reconnectWaitMillis;
    private volatile Consumer<String> sender;

    public PlatformChannel() {
        this(RECONNECT_WAIT_MILLIS);
    }

    PlatformChannel(long reconnectWaitMillis) {
        this.reconnectWaitMillis = reconnectWaitMillis;
    }

    /** 응답을 기다리지 않는다. 연결이 없으면 예외 */
    public void send(String json) {
        Consumer<String> out = sender;
        if (out == null) {
            throw new Disconnected("컨트롤 플레인에 연결돼 있지 않습니다");
        }
        out.accept(json);
    }

    /** 소켓이 열리면 보낼 곳을 넘기고, 닫히면 null 을 넘긴다 */
    public void attach(Consumer<String> sender) {
        synchronized (connection) {
            this.sender = sender;
            connection.notifyAll();
        }
        if (sender == null) {
            pending.values().forEach(f -> f.completeExceptionally(new Disconnected("컨트롤 플레인 연결이 끊겼습니다")));
            pending.clear();
        }
    }

    /**
     * 연결이 없으면 다시 붙을 때까지 기다렸다가 보낸다. 보낸 뒤에 끊기면 다시 보내지 않는다 (같은 요청이 두 번 처리될 수 있다).
     *
     * @param request type 과 본문. rid 는 여기서 붙인다
     * @return 응답의 result
     * @throws Disconnected          기다려도 다시 붙지 않았거나, 응답을 받기 전에 끊겼다
     * @throws IllegalStateException 거절되었거나, 제한 시간 안에 답이 없다
     */
    public JsonNode call(ObjectNode request) {
        Consumer<String> out = awaitSender();
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
            if (e.getCause() instanceof Disconnected disconnected) {
                throw disconnected;
            }
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

    private Consumer<String> awaitSender() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(reconnectWaitMillis);
        synchronized (connection) {
            while (sender == null) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    throw new Disconnected("컨트롤 플레인에 연결돼 있지 않습니다");
                }
                try {
                    connection.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted");
                }
            }
            return sender;
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

    /** 컨트롤 플레인 소켓이 없거나 응답 전에 끊겼다. 요청이 builder 에 닿았는지는 모른다 */
    public static final class Disconnected extends IllegalStateException {
        public Disconnected(String message) {
            super(message);
        }
    }
}
