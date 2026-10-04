package com.lily.onpremise.backup;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * PC 가 받은 분당 요청 수. 1분마다 프록시의 "최근 60초 요청 수"({@code UpstreamProxy.localTraffic()})를 받아
 * 168칸 통계에 더하고, 실행 직전 확인에 쓸 최근 분들을 메모리에 둔다.
 */
public final class RequestHistory {

    /** 1분마다 받는 값이 조금 늦거나 빨라도 그 분으로 본다 */
    private static final Duration SLACK = Duration.ofSeconds(30);

    private final RequestProfile profile;
    private final ZoneId zone;
    private final int keep;
    private final Deque<Minute> recent = new ArrayDeque<>();

    private record Minute(Instant at, int count) {
    }

    /**
     * @param keep 메모리에 둘 최근 분 수 (실행 직전 확인보다 길게)
     */
    public RequestHistory(RequestProfile profile, ZoneId zone, int keep) {
        this.profile = profile;
        this.zone = zone;
        this.keep = keep;
    }

    /**
     * @param now   받은 시각
     * @param count 그 직전 60초의 요청 수. 그 60초의 가운데가 속한 칸에 더한다
     */
    public synchronized void record(Instant now, int count) {
        profile.add(now.minusSeconds(30).atZone(zone), count);
        recent.addLast(new Minute(now, Math.max(0, count)));
        while (recent.size() > keep) {
            recent.removeFirst();
        }
    }

    /**
     * 최근 n 분의 요청 수, 오래된 것부터. 에이전트가 멈췄던 사이는 비어 있으므로 n 개가 안 될 수 있다
     * (그러면 실행 직전 확인을 통과하지 못한다).
     */
    public synchronized List<Integer> lastMinutes(Instant now, int n) {
        Instant from = now.minus(Duration.ofMinutes(n)).minus(SLACK);
        List<Integer> counts = new ArrayList<>();
        for (Minute minute : recent) {
            if (minute.at().isAfter(from) && !minute.at().isAfter(now)) {
                counts.add(minute.count());
            }
        }
        return counts.size() > n ? counts.subList(counts.size() - n, counts.size()) : counts;
    }

    public RequestProfile profile() {
        return profile;
    }
}
