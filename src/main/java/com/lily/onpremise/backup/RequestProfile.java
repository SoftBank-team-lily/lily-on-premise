package com.lily.onpremise.backup;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.Arrays;

/**
 * 요일 × 시간(168칸)마다 PC 가 받은 요청 수 합계와 기록한 분 수. 칸 평균이 "그 시간의 분당 요청 수"다.
 * 원본 기록을 두지 않고 합계만 더하므로 크기가 고정이다.
 */
public final class RequestProfile {

    public static final int SLOTS = 7 * 24;

    private final long[] requests;
    private final long[] minutes;

    public RequestProfile() {
        this(new long[SLOTS], new long[SLOTS]);
    }

    public RequestProfile(long[] requests, long[] minutes) {
        if (requests.length != SLOTS || minutes.length != SLOTS) {
            throw new IllegalArgumentException("칸은 " + SLOTS + " 개다");
        }
        this.requests = requests.clone();
        this.minutes = minutes.clone();
    }

    /** 한 분의 요청 수를 그 분이 속한 칸에 더한다 */
    public synchronized void add(ZonedDateTime minute, int count) {
        int slot = slot(minute.getDayOfWeek(), minute.getHour());
        requests[slot] += Math.max(0, count);
        minutes[slot]++;
    }

    public synchronized long totalMinutes() {
        return Arrays.stream(minutes).sum();
    }

    /** 요일·시간 칸의 분당 평균. 기록이 없으면 NaN */
    public synchronized double mean(DayOfWeek day, int hour) {
        int slot = slot(day, hour);
        return minutes[slot] == 0 ? Double.NaN : (double) requests[slot] / minutes[slot];
    }

    /** 요일을 가리지 않은 시간대의 분당 평균. 기록이 없으면 NaN */
    public synchronized double mean(int hour) {
        long sum = 0;
        long count = 0;
        for (DayOfWeek day : DayOfWeek.values()) {
            int slot = slot(day, hour);
            sum += requests[slot];
            count += minutes[slot];
        }
        return count == 0 ? Double.NaN : (double) sum / count;
    }

    public synchronized long[] requests() {
        return requests.clone();
    }

    public synchronized long[] minutes() {
        return minutes.clone();
    }

    static int slot(DayOfWeek day, int hour) {
        return (day.getValue() - 1) * 24 + hour;
    }
}
