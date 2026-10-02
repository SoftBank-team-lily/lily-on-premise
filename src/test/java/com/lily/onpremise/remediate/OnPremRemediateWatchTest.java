package com.lily.onpremise.remediate;

import com.lily.onpremise.expose.TrafficWindow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OnPremRemediateWatchTest {

    private final List<String> read = new ArrayList<>();
    private final List<String> sent = new ArrayList<>();
    private boolean onPrem = true;
    private TrafficWindow.Sample sample = new TrafficWindow.Sample(60, 6);
    private Optional<String> container = Optional.of("blog-blue");
    private List<String> lines = List.of(
            "java.lang.IllegalStateException: down",
            "\tat com.acme.OrderService.label(OrderService.java:42)");

    @Test
    void secondCriticalSendsOnce() {
        OnPremRemediateWatch watch = watch(true);

        watch.tick();
        watch.tick();
        watch.tick();

        assertThat(read).containsExactly("blog-blue");
        assertThat(sent).containsExactly("IllegalStateException OrderService.java:42");
    }

    @Test
    void disabledWatchDoesNotReadLogs() {
        OnPremRemediateWatch watch = watch(false);

        watch.tick();
        watch.tick();

        assertThat(read).isEmpty();
        assertThat(sent).isEmpty();
    }

    @Test
    void cloudHomeDoesNotSend() {
        OnPremRemediateWatch watch = watch(true);
        watch.tick();
        onPrem = false;
        watch.tick();
        onPrem = true;
        watch.tick();

        assertThat(sent).isEmpty();
    }

    @Test
    void missingFrameDoesNotSendAgainUntilTheLevelDrops() {
        lines = List.of("java.lang.NullPointerException", "\tat org.springframework.web.Foo.bar(Foo.java:10)");
        OnPremRemediateWatch watch = watch(true);

        watch.tick();
        watch.tick();
        watch.tick();

        assertThat(read).containsExactly("blog-blue");
        assertThat(sent).isEmpty();
    }

    @Test
    void failedSendRetriesOnTheNextTick() {
        OnPremRemediateWatch watch = new OnPremRemediateWatch(true, () -> onPrem, () -> "blog", () -> sample,
                () -> container, name -> {
            read.add(name);
            return lines;
        }, incident -> {
            if (sent.isEmpty()) {
                throw new IllegalStateException("socket down");
            }
            sent.add(incident.signature());
        });

        watch.tick();
        watch.tick();
        assertThat(sent).isEmpty();
        assertThat(read).hasSize(1);

        sent.add("opened");
        watch.tick();
        assertThat(read).hasSize(2);
        assertThat(sent).hasSize(2);
    }

    private OnPremRemediateWatch watch(boolean enabled) {
        return new OnPremRemediateWatch(enabled, () -> onPrem, () -> "blog", () -> sample, () -> container, name -> {
            read.add(name);
            return lines;
        }, incident -> sent.add(incident.signature()));
    }
}
