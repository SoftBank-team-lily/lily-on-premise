package com.lily.onpremise.expose;

import java.util.Arrays;

/** 최근 요청 처리 시간. 정해진 시간 안의 표본으로 p95 를 어림한다 (표본 수는 고정, 오래된 것부터 덮어쓴다) */
final class LatencyWindow {

    private final long windowMillis;
    private final long[] at;
    private final long[] millis;
    private int next;

    LatencyWindow(long windowMillis, int capacity) {
        this.windowMillis = windowMillis;
        this.at = new long[capacity];
        this.millis = new long[capacity];
        Arrays.fill(at, Long.MIN_VALUE);
    }

    synchronized void record(long now, long elapsedMillis) {
        at[next] = now;
        millis[next] = elapsedMillis;
        next = (next + 1) % at.length;
    }

    /** @return 창 안에 표본이 없으면 -1 */
    synchronized long p95(long now) {
        long[] recent = new long[at.length];
        int count = 0;
        for (int i = 0; i < at.length; i++) {
            if (at[i] != Long.MIN_VALUE && now - at[i] <= windowMillis) {
                recent[count++] = millis[i];
            }
        }
        if (count == 0) {
            return -1;
        }
        Arrays.sort(recent, 0, count);
        return recent[Math.min(count - 1, (int) Math.ceil(count * 0.95) - 1)];
    }
}
