package com.yongyong.transit.strategy.service;

import com.yongyong.transit.analysis.client.GbisClient;
import com.yongyong.transit.analysis.client.GbisClient.AroundStation;
import com.yongyong.transit.analysis.client.GbisClient.RouteStation;
import com.yongyong.transit.analysis.client.GbisClient.StationRoute;
import com.yongyong.transit.analysis.service.CallBudget;
import com.yongyong.transit.analysis.service.GbisCallLimiter;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 노선·정류장 정보 조회 (대안 생성용 정적 정보).
 * - 노선 경유 정류장 목록(거슬러타기), 정류장 경유 노선 목록·주변 정류장(대체 노선)
 * - 모든 이용자가 같이 쓰는 1일 캐시, 매일 04:00(KST) 기준으로 새로 받는다
 * - 캐시에 없을 때만 하루 한도 + 요청당 한도(budget)를 지키며 GBIS를 부른다
 * - 실패하면 남은 캐시를, 그것도 없으면 빈 목록을 돌려준다 (그 전략만 건너뜀)
 */
@Service
public class RouteStationService {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final int CACHE_RESET_HOUR = 4;

    // 같은 정류장의 다른 승강장으로 볼 거리
    static final int SAME_STATION_METERS = 300;

    private final GbisClient gbisClient;
    private final GbisCallLimiter callLimiter;

    private final Map<String, Cached<List<RouteStation>>> routeStationCache =
        new ConcurrentHashMap<>();
    private final Map<String, Cached<List<StationRoute>>> stationRouteCache =
        new ConcurrentHashMap<>();
    private final Map<String, Cached<List<AroundStation>>> aroundCache =
        new ConcurrentHashMap<>();

    public RouteStationService(
        GbisClient gbisClient,
        GbisCallLimiter callLimiter
    ) {
        this.gbisClient = gbisClient;
        this.callLimiter = callLimiter;
    }

    // 노선이 지나는 정류장 (seq 순서)
    public List<RouteStation> routeStations(
        String routeId,
        CallBudget budget,
        OffsetDateTime now
    ) {
        return lookup(
            routeStationCache,
            routeId,
            GbisCallLimiter.ROUTE_STATION,
            () -> gbisClient.getRouteStations(routeId),
            budget,
            now
        );
    }

    // 정류장을 지나는 노선
    public List<StationRoute> stationRoutes(
        String stationId,
        CallBudget budget,
        OffsetDateTime now
    ) {
        return lookup(
            stationRouteCache,
            stationId,
            GbisCallLimiter.STATION_ROUTE,
            () -> gbisClient.getStationRoutes(stationId),
            budget,
            now
        );
    }

    /**
     * 같은 정류장으로 볼 정류장 ID (자기 자신 먼저).
     * 큰 정류장은 승강장마다 ID가 따로 있어(예: 신분당선강남역(중) 22009·22010)
     * 반경 300m 안에서 이름이 같은 정류장을 같이 본다.
     * station(이름·좌표)이 없으면 자기 자신만.
     */
    public List<String> sameStations(
        RouteStation station,
        CallBudget budget,
        OffsetDateTime now
    ) {
        List<String> result = new ArrayList<>();
        result.add(station.stationId());

        if (station.name() == null || station.x() == null || station.y() == null) {
            return result;
        }

        String name = station.name().trim();

        lookup(
            aroundCache,
            station.stationId(),
            GbisCallLimiter.STATION_AROUND,
            () -> gbisClient.getStationsAround(station.x(), station.y()),
            budget,
            now
        ).stream()
            .filter(around -> around.distance() <= SAME_STATION_METERS)
            .filter(around -> around.name() != null
                && around.name().trim().equals(name))
            .map(AroundStation::stationId)
            .filter(id -> !result.contains(id))
            .forEach(result::add);

        return result;
    }

    /**
     * 대체 노선 후보: 승차 정류장과 하차 정류장(같은 정류장의 다른 승강장 포함)을
     * 모두 지나는 노선 (excludeRouteIds 제외). 노선마다 실제 하차 정류장을 같이 준다.
     * 노선마다 정류장 목록을 받지 않고 정류장 경유 노선 조회로 거른다.
     */
    public List<CommonRoute> commonRoutes(
        String boardStationId,
        List<String> alightStationIds,
        Set<String> excludeRouteIds,
        CallBudget budget,
        OffsetDateTime now
    ) {
        List<StationRoute> boardRoutes =
            stationRoutes(boardStationId, budget, now);
        List<CommonRoute> result = new ArrayList<>();

        if (boardRoutes.isEmpty()) {
            return result;
        }

        Set<String> added = new HashSet<>(excludeRouteIds);

        for (String alightStationId : alightStationIds) {
            Set<String> alightRouteIds = stationRoutes(alightStationId, budget, now)
                .stream()
                .map(StationRoute::routeId)
                .collect(Collectors.toSet());

            for (StationRoute route : boardRoutes) {
                if (alightRouteIds.contains(route.routeId())
                    && added.add(route.routeId())) {
                    result.add(new CommonRoute(
                        route.routeId(), route.routeLabel(), alightStationId
                    ));
                }
            }
        }

        return result;
    }

    /**
     * 승차 정류장의 노선 내 순번.
     * 회차 노선은 같은 정류장이 두 번 나오므로, 하차 정류장보다 앞에 있으면서
     * 가장 가까운 순번을 쓴다. 하차 정류장을 못 찾으면 첫 순번, 승차 정류장이 없으면 null.
     */
    public static Integer boardSeq(
        List<RouteStation> stations,
        String boardStationId,
        String alightStationId
    ) {
        List<Integer> boardSeqs = seqs(stations, boardStationId);
        List<Integer> alightSeqs = seqs(stations, alightStationId);

        if (boardSeqs.isEmpty()) {
            return null;
        }

        Integer best = null;
        int bestGap = Integer.MAX_VALUE;

        for (int board : boardSeqs) {
            for (int alight : alightSeqs) {
                int gap = alight - board;

                if (gap > 0 && gap < bestGap) {
                    best = board;
                    bestGap = gap;
                }
            }
        }

        return best != null ? best : boardSeqs.get(0);
    }

    private static List<Integer> seqs(
        List<RouteStation> stations,
        String stationId
    ) {
        return stations.stream()
            .filter(station -> station.stationId().equals(stationId))
            .map(RouteStation::seq)
            .sorted()
            .toList();
    }

    private <T> List<T> lookup(
        Map<String, Cached<List<T>>> cache,
        String key,
        String apiType,
        Supplier<List<T>> fetcher,
        CallBudget budget,
        OffsetDateTime now
    ) {
        LocalDate cacheDay = cacheDay(now);
        Cached<List<T>> cached = cache.get(key);

        if (cached != null && cached.day().equals(cacheDay)) {
            return cached.data();
        }

        boolean canCall = gbisClient.hasKey()
            && budget.hasRemaining()
            && callLimiter.tryAcquire(
                apiType,
                now.atZoneSameInstant(KST).toLocalDate()
            );

        if (canCall) {
            budget.use();

            try {
                List<T> data = fetcher.get();
                cache.put(key, new Cached<>(data, cacheDay));

                return data;

            } catch (RuntimeException e) {
                // 호출 실패 시 아래에서 지난 캐시를 쓴다
            }
        }

        return cached != null ? cached.data() : List.of();
    }

    // 04:00 전이면 전날 캐시로 본다
    private LocalDate cacheDay(OffsetDateTime now) {
        return now.atZoneSameInstant(KST)
            .minusHours(CACHE_RESET_HOUR)
            .toLocalDate();
    }

    public record CommonRoute(
        String routeId,
        String routeLabel,
        String alightStationId
    ) {
    }

    private record Cached<T>(
        T data,
        LocalDate day
    ) {
    }
}
