package com.yongyong.transit.analysis.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * GBIS 하루 호출 수 관리.
 * apiType별로 따로 세고, 한도의 80% 미만일 때만 호출을 허용한다.
 * 서버 메모리에 저장하므로 날짜가 바뀌거나 서버가 재시작되면 0이 된다.
 */
@Component
public class GbisCallLimiter {

    public static final String ARRIVAL = "ARRIVAL";
    public static final String ROUTE_STATION = "ROUTE_STATION";
    public static final String STATION_ROUTE = "STATION_ROUTE";
    public static final String STATION_AROUND = "STATION_AROUND";

    private static final double ALLOW_RATIO = 0.8;

    private final int dailyLimit;
    private final Map<String, Integer> counts = new HashMap<>();
    private LocalDate countDate;

    public GbisCallLimiter(
        @Value("${GBIS_DAILY_LIMIT:1000}") int dailyLimit
    ) {
        this.dailyLimit = dailyLimit;
    }

    public synchronized boolean tryAcquire(
        String apiType,
        LocalDate today
    ) {
        if (!today.equals(countDate)) {
            counts.clear();
            countDate = today;
        }

        int count = counts.getOrDefault(apiType, 0);

        if (count >= dailyLimit * ALLOW_RATIO) {
            return false;
        }

        counts.put(apiType, count + 1);

        return true;
    }
}
