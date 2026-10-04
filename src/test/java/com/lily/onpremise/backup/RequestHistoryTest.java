package com.lily.onpremise.backup;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RequestHistoryTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 2026-10-04 일요일 03:00 KST */
    private static final Instant SUN_03 = ZonedDateTime.of(2026, 10, 4, 3, 0, 0, 0, KST).toInstant();

    @Test
    void 받은_요청_수는_그_60초의_가운데가_속한_요일_시간_칸에_더한다() {
        RequestHistory history = new RequestHistory(new RequestProfile(), KST, 60);

        // 03:00:10 에 받은 값은 02:59:10~03:00:10 이라 02시 칸이다
        history.record(SUN_03.plusSeconds(10), 4);
        history.record(SUN_03.plusSeconds(70), 6);
        history.record(SUN_03.plusSeconds(130), 8);

        assertThat(history.profile().mean(DayOfWeek.SUNDAY, 2)).isEqualTo(4.0);
        assertThat(history.profile().mean(DayOfWeek.SUNDAY, 3)).isEqualTo(7.0);
        assertThat(history.profile().totalMinutes()).isEqualTo(3);
    }

    @Test
    void 최근_n분을_오래된_것부터_돌려준다() {
        RequestHistory history = new RequestHistory(new RequestProfile(), KST, 60);
        for (int i = 0; i < 15; i++) {
            history.record(SUN_03.plusSeconds(60L * i), i);
        }
        Instant now = SUN_03.plusSeconds(60L * 14);

        assertThat(history.lastMinutes(now, 10)).containsExactly(5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
    }

    @Test
    void 에이전트가_멈췄던_사이는_비어서_n개가_안_된다() {
        RequestHistory history = new RequestHistory(new RequestProfile(), KST, 60);
        for (int i = 0; i < 5; i++) {
            history.record(SUN_03.plusSeconds(60L * i), 0);
        }
        // 20분 쉬고 다시 3분
        for (int i = 25; i < 28; i++) {
            history.record(SUN_03.plusSeconds(60L * i), 0);
        }

        assertThat(history.lastMinutes(SUN_03.plusSeconds(60L * 27), 10)).hasSize(3);
    }

    @Test
    void 메모리에는_정한_분_수만_둔다() {
        RequestHistory history = new RequestHistory(new RequestProfile(), KST, 5);
        for (int i = 0; i < 20; i++) {
            history.record(SUN_03.plusSeconds(60L * i), i);
        }

        assertThat(history.lastMinutes(SUN_03.plusSeconds(60L * 19), 10)).containsExactly(15, 16, 17, 18, 19);
        // 통계에는 모두 남는다
        assertThat(history.profile().totalMinutes()).isEqualTo(20);
    }

    @Test
    void 음수는_0으로_본다() {
        RequestHistory history = new RequestHistory(new RequestProfile(), KST, 60);
        history.record(SUN_03.plusSeconds(10), -3);

        assertThat(history.lastMinutes(SUN_03.plusSeconds(10), 1)).containsExactly(0);
        assertThat(history.profile().mean(DayOfWeek.SUNDAY, 2)).isZero();
    }
}
