package com.yongyong.transit.analysis.service;

import com.yongyong.transit.analysis.client.GbisClient;
import com.yongyong.transit.analysis.client.GbisClient.GbisArrivals;
import com.yongyong.transit.analysis.client.GbisClient.GbisBus;
import com.yongyong.transit.analysis.client.PredictClient;
import com.yongyong.transit.analysis.client.PredictClient.PredictArrival;
import com.yongyong.transit.analysis.client.PredictClient.PredictRequest;
import com.yongyong.transit.analysis.client.PredictClient.PredictResult;
import com.yongyong.transit.analysis.client.PredictClient.Prediction;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.BusArrival;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SeatType;
import com.yongyong.transit.collector.client.CollectorClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 버스 실시간 도착·좌석 조회.
 * 수집 노선 + 수집 정류장이고 수집 데이터가 4분 이내면 수집 서버 값을 쓰고,
 * 그 외에는 60초 캐시 → GBIS 직접 호출 순서로 조회한다.
 * 수집 노선 + 수집 정류장은 예측 서버가 있으면 도착 시각·좌석을 예측값으로 바꾼다.
 */
@Service
public class BusRealtimeService {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final long CACHE_SECONDS = 60;
    private static final long COLLECTOR_FRESH_SECONDS = 240;

    private final GbisClient gbisClient;
    private final GbisCallLimiter callLimiter;
    private final CollectorClient collectorClient;
    private final PredictClient predictClient;

    // 정류장 ID별 마지막 GBIS 응답. 모든 이용자가 같이 쓴다.
    private final Map<String, CachedStation> cache =
        new ConcurrentHashMap<>();

    public BusRealtimeService(
        GbisClient gbisClient,
        GbisCallLimiter callLimiter,
        CollectorClient collectorClient,
        PredictClient predictClient
    ) {
        this.gbisClient = gbisClient;
        this.callLimiter = callLimiter;
        this.collectorClient = collectorClient;
        this.predictClient = predictClient;
    }

    /**
     * 수집 서버의 최신 도착정보를 한 번에 받아 둔다. 분석 요청 1번당 1번만 호출한다.
     * 4분 이내에 수집된 값만 남기고, 수집 서버 오류면 빈 값을 돌려준다.
     * 수집 노선·정류장 목록(예측 대상 판단용)은 신선도와 상관없이 함께 담는다.
     */
    public CollectorSnapshot loadCollectorSnapshot(OffsetDateTime now) {
        try {
            Map<String, String> routeIdByName = new HashMap<>();

            for (JsonNode route : collectorClient.getRoutes()) {
                routeIdByName.put(
                    route.path("route_name").asText(),
                    route.path("route_id").asText()
                );
            }

            Map<String, List<BusArrival>> byStation = new HashMap<>();
            Set<String> collectedStationIds = new HashSet<>();

            for (JsonNode item : collectorClient.getArrivals()) {
                collectedStationIds.add(item.path("station_id").asText());

                String routeId = routeIdByName.get(
                    item.path("route_name").asText()
                );

                OffsetDateTime collectedAt = parseTime(
                    item.path("collected_at").asText()
                );

                JsonNode predictMin = item.path("predict_time_min");

                if (routeId == null
                    || collectedAt == null
                    || !predictMin.isNumber()
                    || !isFresh(collectedAt, now)) {
                    continue;
                }

                Integer remainSeats = seats(item.path("remain_seat_cnt"));

                byStation
                    .computeIfAbsent(
                        item.path("station_id").asText(),
                        key -> new ArrayList<>()
                    )
                    .add(new BusArrival(
                        routeId,
                        item.path("route_name").asText(),
                        item.path("plate_no").asText(),
                        collectedAt.plusMinutes(predictMin.asInt()),
                        remainSeats,
                        seatType(remainSeats),
                        collectedAt,
                        null
                    ));
            }

            return new CollectorSnapshot(
                byStation,
                new HashSet<>(routeIdByName.values()),
                collectedStationIds
            );

        } catch (RuntimeException e) {
            return CollectorSnapshot.empty();
        }
    }

    /**
     * 한 정류장에서 routeIds 노선들의 도착 예정 버스를 조회한다.
     * 수집 서버에 신선한 값이 있는 노선은 그 값을, 나머지 노선은 GBIS(캐시)를 쓴다.
     */
    public StationArrivals getArrivals(
        String stationId,
        Set<String> routeIds,
        CollectorSnapshot snapshot,
        OffsetDateTime now
    ) {
        return getArrivals(
            stationId, routeIds, snapshot, now, CallBudget.unlimited()
        );
    }

    /**
     * 요청당 GBIS 호출 한도(budget)를 지키며 조회한다 (전략 비교·재평가용).
     * 한도를 다 쓰면 GBIS를 부르지 않고 캐시만 쓴다.
     */
    public StationArrivals getArrivals(
        String stationId,
        Set<String> routeIds,
        CollectorSnapshot snapshot,
        OffsetDateTime now,
        CallBudget budget
    ) {
        List<BusArrival> arrivals = new ArrayList<>();
        List<OffsetDateTime> basisTimes = new ArrayList<>();
        boolean delayed = false;

        for (BusArrival arrival : snapshot.arrivals(stationId)) {
            if (routeIds.contains(arrival.routeId())) {
                arrivals.add(arrival);
                basisTimes.add(arrival.dataBasisAt());
            }
        }

        Set<String> remaining = new HashSet<>(routeIds);
        arrivals.forEach(arrival -> remaining.remove(arrival.routeId()));

        if (!remaining.isEmpty()) {
            GbisLookup lookup = lookupGbis(stationId, now, budget);
            delayed = lookup.delayed();

            if (lookup.data() != null) {
                OffsetDateTime basis = lookup.data().queryTime() != null
                    ? lookup.data().queryTime()
                    : lookup.fetchedAt();

                basisTimes.add(basis);

                for (GbisBus bus : lookup.data().buses()) {
                    if (remaining.contains(bus.routeId())) {
                        arrivals.add(toArrival(bus, basis));
                    }
                }
            }
        }

        List<BusArrival> upcoming = upcoming(
            applyPredictions(stationId, upcoming(arrivals, now), snapshot, now),
            now
        );

        OffsetDateTime dataBasisAt = basisTimes.stream()
            .min(Comparator.naturalOrder())
            .orElse(null);

        return new StationArrivals(upcoming, dataBasisAt, delayed);
    }

    private List<BusArrival> upcoming(
        List<BusArrival> arrivals,
        OffsetDateTime now
    ) {
        return arrivals.stream()
            .filter(arrival -> !arrival.arrivalAt().isBefore(now))
            .sorted(Comparator.comparing(BusArrival::arrivalAt))
            .toList();
    }

    /**
     * 수집 노선 + 수집 정류장이면 (노선, 정류장)마다 예측 서버를 호출해
     * 차량번호가 같은 버스의 도착 시각·좌석을 예측값으로 바꾼다.
     * 예측 서버가 없거나 실패하면 실시간 값을 그대로 둔다.
     */
    private List<BusArrival> applyPredictions(
        String stationId,
        List<BusArrival> arrivals,
        CollectorSnapshot snapshot,
        OffsetDateTime now
    ) {
        if (!predictClient.isEnabled()
            || !snapshot.collectedStationIds().contains(stationId)) {
            return arrivals;
        }

        Map<String, List<BusArrival>> byRoute = arrivals.stream()
            .filter(arrival -> snapshot.collectedRouteIds()
                .contains(arrival.routeId()))
            .collect(Collectors.groupingBy(BusArrival::routeId));

        Map<BusArrival, BusArrival> replaced = new HashMap<>();

        byRoute.forEach((routeId, routeArrivals) -> {
            PredictResult result;

            try {
                result = predictClient.predict(new PredictRequest(
                    routeId,
                    stationId,
                    now,
                    routeArrivals.stream()
                        .map(arrival -> new PredictArrival(
                            arrival.plateNo(),
                            arrival.arrivalAt(),
                            arrival.remainSeats()
                        ))
                        .toList()
                ));
            } catch (RuntimeException e) {
                return;
            }

            Map<String, Prediction> byPlate = new HashMap<>();
            result.predictions().forEach(prediction ->
                byPlate.put(prediction.plateNo(), prediction)
            );

            for (BusArrival arrival : routeArrivals) {
                Prediction prediction = byPlate.get(arrival.plateNo());

                if (prediction != null) {
                    replaced.put(
                        arrival,
                        predicted(arrival, prediction, result.modelVersion())
                    );
                }
            }
        });

        return arrivals.stream()
            .map(arrival -> replaced.getOrDefault(arrival, arrival))
            .toList();
    }

    private BusArrival predicted(
        BusArrival arrival,
        Prediction prediction,
        String modelVersion
    ) {
        boolean hasSeats = prediction.predictedRemainSeats() != null;

        return new BusArrival(
            arrival.routeId(),
            arrival.routeLabel(),
            arrival.plateNo(),
            prediction.predictedBusArrivalAt() != null
                ? prediction.predictedBusArrivalAt()
                : arrival.arrivalAt(),
            hasSeats ? prediction.predictedRemainSeats() : arrival.remainSeats(),
            hasSeats ? SeatType.PREDICTED : arrival.seatType(),
            arrival.dataBasisAt(),
            modelVersion
        );
    }

    private GbisLookup lookupGbis(
        String stationId,
        OffsetDateTime now,
        CallBudget budget
    ) {
        CachedStation cached = cache.get(stationId);

        if (cached != null
            && Duration.between(cached.fetchedAt(), now).getSeconds()
                < CACHE_SECONDS) {
            return new GbisLookup(cached.data(), cached.fetchedAt(), false);
        }

        boolean canCall = gbisClient.hasKey()
            && budget.hasRemaining()
            && callLimiter.tryAcquire(
                GbisCallLimiter.ARRIVAL,
                now.atZoneSameInstant(KST).toLocalDate()
            );

        if (canCall) {
            budget.use();

            try {
                GbisArrivals data = gbisClient.getStationArrivals(stationId);
                cache.put(stationId, new CachedStation(data, now));

                return new GbisLookup(data, now, false);

            } catch (RuntimeException e) {
                // 호출 실패 시 아래에서 마지막 캐시를 쓴다
            }
        }

        // 키 없음, 한도 초과, 호출 실패: 오래된 캐시라도 쓰고 지연으로 표시
        if (cached == null) {
            return new GbisLookup(null, null, true);
        }

        return new GbisLookup(cached.data(), cached.fetchedAt(), true);
    }

    private BusArrival toArrival(
        GbisBus bus,
        OffsetDateTime basis
    ) {
        Integer remainSeats = bus.remainSeats() == null
            || bus.remainSeats() < 0
            ? null
            : bus.remainSeats();

        return new BusArrival(
            bus.routeId(),
            bus.routeName(),
            bus.plateNo(),
            basis.plusSeconds(bus.predictSec()),
            remainSeats,
            seatType(remainSeats),
            basis,
            null
        );
    }

    // 좌석 -1(비좌석) 또는 값 없음은 null
    private Integer seats(JsonNode value) {
        if (!value.isNumber() || value.asInt() < 0) {
            return null;
        }

        return value.asInt();
    }

    private SeatType seatType(Integer remainSeats) {
        return remainSeats == null
            ? SeatType.NONE
            : SeatType.OBSERVED;
    }

    private boolean isFresh(
        OffsetDateTime collectedAt,
        OffsetDateTime now
    ) {
        long ageSeconds = Duration.between(collectedAt, now).getSeconds();

        return ageSeconds < COLLECTOR_FRESH_SECONDS
            && ageSeconds > -60;
    }

    private OffsetDateTime parseTime(String value) {
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    public record CollectorSnapshot(
        Map<String, List<BusArrival>> byStation,
        Set<String> collectedRouteIds,
        Set<String> collectedStationIds
    ) {
        public static CollectorSnapshot empty() {
            return new CollectorSnapshot(Map.of(), Set.of(), Set.of());
        }

        public List<BusArrival> arrivals(String stationId) {
            return byStation.getOrDefault(stationId, List.of());
        }
    }

    public record StationArrivals(
        List<BusArrival> arrivals,
        OffsetDateTime dataBasisAt,
        boolean delayed
    ) {
    }

    private record CachedStation(
        GbisArrivals data,
        OffsetDateTime fetchedAt
    ) {
    }

    private record GbisLookup(
        GbisArrivals data,
        OffsetDateTime fetchedAt,
        boolean delayed
    ) {
    }
}
