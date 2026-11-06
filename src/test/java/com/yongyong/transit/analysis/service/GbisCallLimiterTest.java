package com.yongyong.transit.analysis.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class GbisCallLimiterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);

    @Test
    void 한도_1000이면_800회까지_허용하고_801회째부터_거부() {
        GbisCallLimiter limiter = new GbisCallLimiter(1000);

        for (int i = 0; i < 800; i++) {
            assertThat(limiter.tryAcquire(GbisCallLimiter.ARRIVAL, TODAY))
                .isTrue();
        }

        assertThat(limiter.tryAcquire(GbisCallLimiter.ARRIVAL, TODAY))
            .isFalse();
    }

    @Test
    void 날짜가_바뀌면_다시_허용() {
        GbisCallLimiter limiter = new GbisCallLimiter(10);

        for (int i = 0; i < 8; i++) {
            limiter.tryAcquire(GbisCallLimiter.ARRIVAL, TODAY);
        }

        assertThat(limiter.tryAcquire(GbisCallLimiter.ARRIVAL, TODAY))
            .isFalse();
        assertThat(limiter.tryAcquire(GbisCallLimiter.ARRIVAL, TODAY.plusDays(1)))
            .isTrue();
    }

    @Test
    void apiType별로_따로_센다() {
        GbisCallLimiter limiter = new GbisCallLimiter(10);

        for (int i = 0; i < 8; i++) {
            limiter.tryAcquire(GbisCallLimiter.ARRIVAL, TODAY);
        }

        assertThat(limiter.tryAcquire("LOCATION", TODAY)).isTrue();
    }
}
