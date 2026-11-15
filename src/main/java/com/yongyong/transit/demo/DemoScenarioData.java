package com.yongyong.transit.demo;

import com.yongyong.transit.analysis.service.BusRealtimeService.CollectorSnapshot;
import com.yongyong.transit.analysis.service.BusRealtimeService.StationArrivals;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.BusArrival;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SeatType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Separate 2026-11-16 demo fixture; never calls GBIS/collector/prediction endpoints.
 * Applies only while the Spring "demo" profile is active.
 */
@Component
@Profile("demo")
public class DemoScenarioData {

    public static final String BOARD_STATION_ID = "228000172";
    public static final String BUS_5003B_ID = "228000182";
    public static final String BUS_5003A_ID = "228000431";

    public CollectorSnapshot snapshot(OffsetDateTime now) {
        return new CollectorSnapshot(
            Map.of(BOARD_STATION_ID, buses(now)),
            Set.of(BUS_5003B_ID, BUS_5003A_ID)
        );
    }

    public StationArrivals getArrivals(
        String stationId,
        Set<String> routeIds,
        OffsetDateTime now
    ) {
        if (!BOARD_STATION_ID.equals(stationId)
            || routeIds == null || routeIds.isEmpty()) {
            return new StationArrivals(List.of(), null, false);
        }

        List<BusArrival> matched = new ArrayList<>();
        for (BusArrival bus : buses(now)) {
            if (routeIds.contains(bus.routeId())
                && !bus.arrivalAt().isBefore(now)) {
                matched.add(bus);
            }
        }
        return new StationArrivals(List.copyOf(matched), now, false);
    }

    public List<BusArrival> buses(OffsetDateTime now) {
        return List.of(
            bus(BUS_5003B_ID, "5003B", "DEMO-001",
                "2026-11-16T08:18:00+09:00", 0, now),
            bus(BUS_5003B_ID, "5003B", "DEMO-002",
                "2026-11-16T08:28:00+09:00", 8, now),
            bus(BUS_5003A_ID, "5003A", "DEMO-003",
                "2026-11-16T08:24:00+09:00", 8, now)
        );
    }

    private static BusArrival bus(
        String routeId,
        String routeLabel,
        String plate,
        String arrival,
        int seats,
        OffsetDateTime now
    ) {
        return new BusArrival(
            routeId,
            routeLabel,
            plate,
            OffsetDateTime.parse(arrival),
            seats,
            SeatType.PREDICTED,
            now,
            "demo"
        );
    }
}
