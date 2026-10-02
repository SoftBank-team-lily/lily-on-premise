package com.lily.onpremise.cutover;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * 공개 주소를 에이전트 바깥에서 연다. 5xx 와 연결 실패는 실패다.
 *
 * <p>CNAME 을 바꾼 직후 몇 초는 Cloudflare 가장자리가 새 오리진으로 다 넘어가지 않아 오류나 시간 초과가 섞인다
 * (실측: 바꾼 뒤 2~4초). 한 번 실패로 되돌리지 않게 {@link #BUDGET_MILLIS} 동안 다시 연다.
 */
final class PublicProbe {

    static final long BUDGET_MILLIS = 30_000;
    private static final long INTERVAL_MILLIS = 2_000;

    private PublicProbe() {
    }

    static boolean ok(String url) {
        return retry(url, PublicProbe::once, System::currentTimeMillis, PublicProbe::sleep);
    }

    /** 성공하면 바로 참, 예산 안에서 계속 실패하면 거짓 */
    static boolean retry(String url, Predicate<String> once, LongSupplier clock, Sleeper sleeper) {
        long deadline = clock.getAsLong() + BUDGET_MILLIS;
        while (true) {
            if (once.test(url)) {
                return true;
            }
            if (clock.getAsLong() + INTERVAL_MILLIS > deadline) {
                return false;
            }
            if (!sleeper.sleep(INTERVAL_MILLIS)) {
                return false;
            }
        }
    }

    static boolean once(String url) {
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            int code = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return code < 500;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @FunctionalInterface
    interface Sleeper {
        /** @return 끼어들기 없이 쉬었으면 참 */
        boolean sleep(long millis);
    }
}
