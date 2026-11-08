package com.yongyong.transit.strategy.service;

import com.yongyong.transit.analysis.service.BusRealtimeService;
import com.yongyong.transit.analysis.service.BusRealtimeService.CollectorSnapshot;
import com.yongyong.transit.analysis.service.BusRealtimeService.StationArrivals;
import com.yongyong.transit.analysis.service.CallBudget;
import com.yongyong.transit.analysis.service.RouteAnalysisService;
import com.yongyong.transit.analysis.service.RouteAnalysisService.RouteInput;
import com.yongyong.transit.analysis.service.RouteAnalysisService.RouteSegment;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.BusArrival;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 전략 요청 1번 동안 쓰는 실시간 도착정보 모음.
 * 같은 (정류장, 노선)은 한 번만 조회하고, 같은 정류장에 노선이 늘면 그 노선만 더 조회해 합친다.
 * 수집 서버 데이터는 처음 조회할 때 한 번만 받는다.
 */
class ArrivalStore {

    private static final String MATCHED = "MATCHED";

    private final BusRealtimeService busRealtimeService;
    private final RouteAnalysisService routeAnalysisService;
    private final OffsetDateTime now;
    private final CallBudget budget;

    private final Map<String, StationArrivals> byStation = new HashMap<>();
    private final Map<String, Set<String>> loadedRouteIds = new HashMap<>();
    private CollectorSnapshot snapshot;

    ArrivalStore(
        BusRealtimeService busRealtimeService,
        RouteAnalysisService routeAnalysisService,
        OffsetDateTime now,
        CallBudget budget
    ) {
        this.busRealtimeService = busRealtimeService;
        this.routeAnalysisService = routeAnalysisService;
        this.now = now;
        this.budget = budget;
    }

    // 경로의 MATCHED 버스 정류장을 모두 조회
    void load(RouteInput route) {
        for (RouteSegment segment : route.segments()) {
            if (MATCHED.equals(routeAnalysisService.matchStatus(segment))) {
                load(
                    segment.startLocalStationId(),
                    routeAnalysisService.gbisRouteIds(segment)
                );
            }
        }
    }

    void load(String stationId, Set<String> routeIds) {
        Set<String> missing = new HashSet<>(routeIds);
        missing.removeAll(loadedRouteIds.getOrDefault(stationId, Set.of()));

        if (missing.isEmpty()) {
            return;
        }

        if (snapshot == null) {
            snapshot = busRealtimeService.loadCollectorSnapshot(now);
        }

        StationArrivals loaded = busRealtimeService.getArrivals(
            stationId, missing, snapshot, now, budget
        );

        loadedRouteIds
            .computeIfAbsent(stationId, key -> new HashSet<>())
            .addAll(missing);
        byStation.merge(stationId, loaded, ArrivalStore::merge);
    }

    // 조회한 값을 고쳐 넣는다 (거슬러타기 좌석 보정)
    void replace(String stationId, StationArrivals arrivals) {
        byStation.put(stationId, arrivals);
    }

    Map<String, StationArrivals> arrivals() {
        return byStation;
    }

    CallBudget budget() {
        return budget;
    }

    private static StationArrivals merge(StationArrivals a, StationArrivals b) {
        List<BusArrival> arrivals = new ArrayList<>(a.arrivals());
        arrivals.addAll(b.arrivals());

        OffsetDateTime basis = a.dataBasisAt() == null
            ? b.dataBasisAt()
            : b.dataBasisAt() == null || a.dataBasisAt().isBefore(b.dataBasisAt())
                ? a.dataBasisAt()
                : b.dataBasisAt();

        return new StationArrivals(arrivals, basis, a.delayed() || b.delayed());
    }
}
