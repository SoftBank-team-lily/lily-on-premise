package com.lily.onpremise.expose;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 후보와 지금 트래픽을 받는 슬롯에 루프백으로 같은 경로를 보낸다.
 * 공개 프록시의 upstream 은 바꾸지 않는다. 5xx 와 연결 실패만 에러다.
 * 기준은 클라우드 블루그린 판정과 같다. 30초, 최소 20응답, 에러율 5%, p95 1초.
 *
 * <p>후보가 처음 HTTP 로 응답하기 전의 연결 실패는 기동 중으로 보고 세지 않는다. 헬스가 tcp 면 Docker 포트 프록시가
 * 앱보다 먼저 연결을 받아 헬스가 바로 통과하고, 앱이 뜨는 몇 초 동안 연결이 끊긴다. 끝까지 응답하지 않으면
 * 응답 수가 모자라 거절한다.
 */
public final class LoopbackCandidateJudge implements CandidateJudge {

    static final int EARLY_ABORT_SAMPLES = 10;
    static final double EARLY_ABORT_ERROR_RATE = 0.5;

    private final HttpClient http;
    private final Duration duration;
    private final int intervalMillis;
    private final int requestTimeoutMillis;
    private final int minSamples;
    private final double maxErrorRate;
    private final long maxP95Millis;
    private final double maxP95Ratio;
    private final long minP95RegressionMillis;

    public LoopbackCandidateJudge(HttpClient http) {
        this(http, Duration.ofSeconds(30), 250, 2000, 20, 0.05, 1000, 2.0, 100);
    }

    LoopbackCandidateJudge(HttpClient http, Duration duration, int intervalMillis, int requestTimeoutMillis,
                           int minSamples, double maxErrorRate, long maxP95Millis,
                           double maxP95Ratio, long minP95RegressionMillis) {
        this.http = http;
        this.duration = duration;
        this.intervalMillis = intervalMillis;
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.minSamples = minSamples;
        this.maxErrorRate = maxErrorRate;
        this.maxP95Millis = maxP95Millis;
        this.maxP95Ratio = maxP95Ratio;
        this.minP95RegressionMillis = minP95RegressionMillis;
    }

    @Override
    public Optional<String> reject(int candidatePort, int activePort, String path) {
        String httpPath = path == null || path.isBlank() ? "/" : path;
        if (!httpPath.startsWith("/")) {
            httpPath = "/" + httpPath;
        }
        List<Sample> newer = new ArrayList<>();
        List<Sample> older = new ArrayList<>();
        long deadline = System.nanoTime() + duration.toNanos();
        boolean answered = false;
        while (System.nanoTime() < deadline) {
            Sample candidate = sample(candidatePort, httpPath);
            answered = answered || candidate.answered();
            if (answered) {
                newer.add(candidate);
                older.add(sample(activePort, httpPath));
            }
            if (newer.size() >= EARLY_ABORT_SAMPLES) {
                Stats early = Stats.of(newer);
                if (early.errorRate() >= EARLY_ABORT_ERROR_RATE) {
                    return Optional.of("error rate " + percent(early.errorRate())
                            + " >= " + percent(EARLY_ABORT_ERROR_RATE));
                }
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("judge interrupted");
            }
        }
        return Optional.ofNullable(decide(Stats.of(newer), Stats.of(older)));
    }

    private Sample sample(int port, String path) {
        long started = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofMillis(requestTimeoutMillis))
                    .GET()
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            long millis = (System.nanoTime() - started) / 1_000_000;
            return new Sample(millis, response.statusCode() >= 500, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("judge interrupted");
        } catch (Exception e) {
            long millis = (System.nanoTime() - started) / 1_000_000;
            return new Sample(millis, true, false);
        }
    }

    /** @return 거절 이유. 통과면 null */
    String decide(Stats newer, Stats older) {
        if (newer.total() < minSamples) {
            return "too few responses " + newer.total() + " < " + minSamples;
        }
        if (newer.errorRate() > maxErrorRate) {
            return "error rate " + percent(newer.errorRate()) + " > " + percent(maxErrorRate);
        }
        if (newer.p95() > maxP95Millis) {
            return "p95 " + newer.p95() + "ms > " + maxP95Millis + "ms";
        }
        if (older.total() >= minSamples && older.errorRate() <= maxErrorRate
                && newer.p95() > older.p95() * maxP95Ratio
                && newer.p95() - older.p95() >= minP95RegressionMillis) {
            return "p95 " + newer.p95() + "ms > " + maxP95Ratio + "x old " + older.p95() + "ms";
        }
        return null;
    }

    private static String percent(double rate) {
        return String.format("%.1f%%", rate * 100);
    }

    /** @param answered HTTP 응답을 받았다 (5xx 포함). 연결 실패·시간 초과면 거짓 */
    record Sample(long millis, boolean failed, boolean answered) {
    }

    record Stats(int total, double errorRate, long p95) {
        static Stats of(List<Sample> samples) {
            if (samples.isEmpty()) {
                return new Stats(0, 0, 0);
            }
            long errors = samples.stream().filter(Sample::failed).count();
            List<Long> millis = new ArrayList<>(samples.stream().map(Sample::millis).toList());
            Collections.sort(millis);
            int index = (int) Math.ceil(millis.size() * 0.95) - 1;
            return new Stats(samples.size(), (double) errors / samples.size(), millis.get(Math.max(0, index)));
        }
    }
}
