package com.lily.onpremise.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformChannelTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 공용 풀은 코어가 적으면 작업이 늦게 시작해서 끊는 시점보다 뒤에 보낼 수 있다 */
    private static final ExecutorService THREADS = Executors.newVirtualThreadPerTaskExecutor();

    @Test
    void 연결이_없을_때_call하면_다시_연결된_뒤에_보내고_응답을_돌려준다() throws Exception {
        PlatformChannel channel = new PlatformChannel(5_000);
        List<String> sent = new CopyOnWriteArrayList<>();

        CompletableFuture<JsonNode> result = CompletableFuture.supplyAsync(
                () -> channel.call(PlatformChannel.request("burst")), THREADS);
        Thread.sleep(300);
        assertTrue(sent.isEmpty());
        channel.attach(answering(channel, sent));

        assertEquals("ok", result.get(3, TimeUnit.SECONDS).path("value").asText());
        assertEquals(1, sent.size());
    }

    @Test
    void 연결이_대기_시간_안에_돌아오지_않으면_Disconnected로_실패한다() {
        PlatformChannel channel = new PlatformChannel(200);

        assertThrows(PlatformChannel.Disconnected.class, () -> channel.call(PlatformChannel.request("burst")));
    }

    @Test
    void 응답을_받기_전에_연결이_끊기면_Disconnected로_실패하고_다시_보내지_않는다() throws Exception {
        PlatformChannel channel = new PlatformChannel(200);
        List<String> sent = new CopyOnWriteArrayList<>();
        channel.attach(sent::add);

        CompletableFuture<JsonNode> result = CompletableFuture.supplyAsync(
                () -> channel.call(PlatformChannel.request("burst")), THREADS);
        awaitSent(sent, 1);
        channel.attach(null);

        Exception e = assertThrows(Exception.class, () -> result.get(3, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof PlatformChannel.Disconnected);
        assertEquals(1, sent.size());
    }

    @Test
    void GET_조회_응답_전에_끊기면_다시_연결된_뒤_한_번_더_보낸다() throws Exception {
        PlatformChannel channel = new PlatformChannel(5_000);
        RelayBurstClient client = new RelayBurstClient(channel, JSON);
        List<String> sent = new CopyOnWriteArrayList<>();
        channel.attach(sent::add);

        CompletableFuture<?> state = CompletableFuture.supplyAsync(() -> client.build("b1"), THREADS);
        awaitSent(sent, 1);
        channel.attach(null);
        channel.attach(answering(channel, sent));

        state.get(3, TimeUnit.SECONDS);
        assertEquals(2, sent.size());
    }

    @Test
    void PUT_레플리카_요청_응답_전에_끊기면_다시_보내지_않고_실패한다() throws Exception {
        PlatformChannel channel = new PlatformChannel(5_000);
        RelayBurstClient client = new RelayBurstClient(channel, JSON);
        List<String> sent = new CopyOnWriteArrayList<>();
        channel.attach(sent::add);

        CompletableFuture<?> scale = CompletableFuture.runAsync(() -> client.scale("app", 1), THREADS);
        awaitSent(sent, 1);
        channel.attach(null);
        channel.attach(answering(channel, sent));

        Exception e = assertThrows(Exception.class, () -> scale.get(3, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof PlatformChannel.Disconnected);
        assertEquals(1, sent.size());
    }

    private static void awaitSent(List<String> sent, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (sent.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(count, sent.size());
    }

    /** 보낸 요청을 기록하고, 같은 rid 로 성공 응답을 돌려준다 */
    private static Consumer<String> answering(PlatformChannel channel, List<String> sent) {
        return text -> {
            sent.add(text);
            try {
                JsonNode request = JSON.readTree(text);
                ObjectNode reply = JSON.createObjectNode();
                reply.put("rid", request.path("rid").asText());
                reply.put("ok", true);
                reply.set("result", JSON.createObjectNode().put("value", "ok").put("status", "SUCCEEDED"));
                CompletableFuture.runAsync(() -> channel.complete(reply), THREADS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }
}
