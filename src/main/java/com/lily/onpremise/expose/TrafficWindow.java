package com.lily.onpremise.expose;

/**
 * 최근 1분의 요청 수와 5xx 수. 초마다 칸 하나라 메모리는 고정이다.
 */
public final class TrafficWindow {

    private static final long MINUTE_MILLIS = 60_000L;
    private static final int MIN_REQUESTS = 20;

    private final long[] at = new long[60];
    private final int[] requests = new int[60];
    private final int[] errors = new int[60];

    public synchronized void record(long now, boolean error) {
        int slot = (int) Math.floorMod(now / 1000, 60);
        if (at[slot] / 1000 != now / 1000) {
            at[slot] = now;
            requests[slot] = 0;
            errors[slot] = 0;
        }
        requests[slot]++;
        if (error) {
            errors[slot]++;
        }
    }

    public synchronized Sample lastMinute(long now) {
        int total = 0;
        int failed = 0;
        for (int i = 0; i < at.length; i++) {
            if (at[i] != 0 && now - at[i] <= MINUTE_MILLIS) {
                total += requests[i];
                failed += errors[i];
            }
        }
        return new Sample(total, failed);
    }

    /** 최근 1분이 20건 이상이고 5xx 가 5% 이상 */
    public record Sample(int requests, int errors) {
        public boolean critical() {
            return requests >= MIN_REQUESTS && errors * 20L >= requests;
        }
    }
}
