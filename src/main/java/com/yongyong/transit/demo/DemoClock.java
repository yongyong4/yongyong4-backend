
package com.yongyong.transit.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Profile("demo")
public class DemoClock {

    private static final ZoneOffset KST =
        ZoneOffset.ofHours(9);

    private static final OffsetDateTime INITIAL_TIME =
        OffsetDateTime.parse("2026-11-16T08:10:00+09:00");

    private final AtomicReference<OffsetDateTime> currentTime =
        new AtomicReference<>(INITIAL_TIME);

    public OffsetDateTime now() {
        return currentTime.get();
    }

    public OffsetDateTime reset() {
        currentTime.set(INITIAL_TIME);
        return currentTime.get();
    }

    public OffsetDateTime set(OffsetDateTime time) {
        if (time == null) {
            throw new IllegalArgumentException(
                "가상 시각이 필요합니다."
            );
        }

        OffsetDateTime normalized =
            time.withOffsetSameInstant(KST);

        currentTime.set(normalized);
        return normalized;
    }

    public OffsetDateTime advance(long minutes) {
        if (minutes < 0 || minutes > 180) {
            throw new IllegalArgumentException(
                "0~180분 사이로 설정해주세요."
            );
        }

        return currentTime.updateAndGet(
            time -> time.plusMinutes(minutes)
        );
    }
}
