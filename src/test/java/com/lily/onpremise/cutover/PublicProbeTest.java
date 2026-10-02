package com.lily.onpremise.cutover;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicProbeTest {

    private final long[] now = {0};

    @Test
    void CNAME_을_바꾼_직후의_실패는_다시_열어_본다() {
        ArrayDeque<Boolean> answers = new ArrayDeque<>(List.of(false, false, true));

        boolean ok = PublicProbe.retry("https://blog.lily.dev/health", url -> answers.poll(), () -> now[0],
                millis -> {
                    now[0] += millis;
                    return true;
                });

        assertThat(ok).isTrue();
        assertThat(now[0]).isEqualTo(4_000);
    }

    @Test
    void 예산_동안_계속_실패하면_실패다() {
        int[] calls = {0};

        boolean ok = PublicProbe.retry("https://blog.lily.dev/health", url -> {
            calls[0]++;
            return false;
        }, () -> now[0], millis -> {
            now[0] += millis;
            return true;
        });

        assertThat(ok).isFalse();
        assertThat(now[0]).isLessThanOrEqualTo(PublicProbe.BUDGET_MILLIS);
        assertThat(calls[0]).isEqualTo(16);
    }
}
