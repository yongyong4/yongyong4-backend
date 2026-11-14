package com.yongyong.transit.strategy.service;

import com.yongyong.transit.analysis.client.GbisClient.RouteStation;
import com.yongyong.transit.analysis.client.GbisClient.StationRoute;
import com.yongyong.transit.analysis.service.BusRealtimeService.StationArrivals;
import com.yongyong.transit.analysis.service.CallBudget;
import com.yongyong.transit.analysis.service.RouteAnalysisService;
import com.yongyong.transit.analysis.service.RouteAnalysisService.BusOption;
import com.yongyong.transit.analysis.service.RouteAnalysisService.RouteInput;
import com.yongyong.transit.analysis.service.RouteAnalysisService.RoutePlan;
import com.yongyong.transit.analysis.service.RouteAnalysisService.RouteSegment;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.BusArrival;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SeatType;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SegmentResult;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.Stage;
import com.yongyong.transit.route.walking.service.WalkingRouteService;
import com.yongyong.transit.route.walking.service.WalkingRouteService.WalkingRouteResponse;
import com.yongyong.transit.strategy.service.RouteStationService.CommonRoute;
import com.yongyong.transit.strategy.service.StrategyService.Location;
import com.yongyong.transit.strategy.service.StrategyService.StrategyType;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 대안 생성.
 * 고른 경로(재평가는 남은 구간)의 첫 MATCHED 버스 구간을 바꾸거나 다른 후보 경로를 가져와
 * 경로 따라가기로 계산한다. 순서: 대체 노선 → 거슬러타기 → 우회 → 다른 교통수단.
 * - 대체 노선: 같은 승차·하차 정류장(하차는 같은 이름의 다른 승강장 포함)을 지나는
 *   다른 경기 노선 (GBIS 노선 정보). 항상 만든다
 * - 거슬러타기: 같은 노선의 이전 정류장. 현재 위치가 있고 버스에 타 있지 않을 때만.
 *   도보 시간은 카카오 도보 API (실패하면 직선거리로 대신)
 * - 우회·다른 교통수단: 다른 후보 경로(출발지 기준) 그대로. 버스에 타 있지 않을 때만
 * GBIS 호출은 요청당 한도(budget) 안에서만 하고, 한도를 넘으면 캐시만 쓴다.
 */
@Service
public class AlternativeService {

    static final int MAX_PER_TYPE = 5;
    static final int MAX_WALK_MIN = 15;
    static final int MIN_PER_STATION = 2;

    private static final long WALK_API_TIMEOUT_MS = 2000;
    private static final long CURRENT_WALK_API_TIMEOUT_MS = 1000;
    private static final String WALK_ROUTE_MODE = "SHORTEST";

    private static final double WALK_DETOUR_RATIO = 1.3;
    private static final double WALK_METERS_PER_MIN = 67;
    private static final double EARTH_RADIUS_M = 6_371_000;

    private static final String BUS = "BUS";
    private static final String WALK = "WALK";
    private static final String SUBWAY = "SUBWAY";

    private final RouteAnalysisService routeAnalysisService;
    private final RouteStationService routeStationService;
    private final WalkingRouteService walkingRouteService;

    public AlternativeService(
        RouteAnalysisService routeAnalysisService,
        RouteStationService routeStationService,
        WalkingRouteService walkingRouteService
    ) {
        this.routeAnalysisService = routeAnalysisService;
        this.routeStationService = routeStationService;
        this.walkingRouteService = walkingRouteService;
    }

    List<Candidate> generate(Context context) {
        List<Candidate> result = new ArrayList<>();
        int busIndex = firstMatchedIndex(context.keepPlan());

        // 분석할 버스가 없으면(지하철만) 대안을 만들지 않는다
        if (busIndex < 0) {
            return result;
        }

        boolean boarded = context.baseTime().isAfter(context.now());

        result.addAll(altRoutes(context, busIndex));

        if (!boarded) {
            result.addAll(backtracks(context, busIndex));
            result.addAll(otherRoutes(context, busIndex));
        }

        return result;
    }

    /**
     * 대체 노선: 승차·하차 정류장을 모두 지나는 다른 노선으로 버스만 바꾼다.
     * 하차 정류장은 같은 이름의 다른 승강장(반경 300m)도 같은 곳으로 보고,
     * 그 뒤 도보 구간은 그대로 둔다.
     * 탑승 시간은 다른 후보 경로에 같은 구간·노선이 있으면 그 값,
     * 없으면 정류장 수 × 2분과 원래 탑승 시간 중 큰 값.
     */
    private List<Candidate> altRoutes(Context context, int busIndex) {
        RouteSegment bus = context.route().segments().get(busIndex);
        String board = bus.startLocalStationId();
        String alight = bus.endLocalStationId();
        List<Candidate> result = new ArrayList<>();

        if (alight == null || alight.isBlank()) {
            return result;
        }

        Set<String> exclude = new HashSet<>();
        bus.busOptions().forEach(option -> {
            if (option != null && option.localRouteId() != null) {
                exclude.add(option.localRouteId());
            }
        });

        List<String> alights = sameStations(context, busIndex);

        List<CommonRoute> routes = routeStationService.commonRoutes(
            board, alights, exclude, context.store().budget(), context.now()
        );

        int count = 0;

        for (CommonRoute route : routes) {
            if (count >= MAX_PER_TYPE) {
                break;
            }

            Integer rideMin = altRideMin(
                context, bus, route.routeId(), route.alightStationId()
            );

            // 다른 승강장인데 방향을 확인 못 하면 뺀다
            if (rideMin == null) {
                continue;
            }

            count++;

            RouteSegment changed = new RouteSegment(
                bus.segmentIndex(), BUS, rideMin,
                bus.startName(), bus.endName(),
                board, route.alightStationId(), null,
                List.of(new BusOption(route.routeLabel(), route.routeId()))
            );

            List<RouteSegment> segments =
                new ArrayList<>(context.route().segments());
            segments.set(busIndex, changed);

            evaluate(
                context,
                StrategyType.ALT_ROUTE,
                route.routeLabel() + "번으로 바꾸기",
                withSegments(context.route(), segments),
                busIndex,
                null, null, null,
                0
            ).ifPresent(result::add);
        }

        return result;
    }

    // 원래 하차 정류장과 같은 이름의 다른 승강장 (원래 노선의 정류장 목록에서 이름·좌표를 찾는다)
    private List<String> sameStations(Context context, int busIndex) {
        RouteSegment bus = context.route().segments().get(busIndex);
        String alight = bus.endLocalStationId();
        SegmentResult keepBus = context.keepPlan().segments().get(busIndex).result();
        String routeId = keepBus.routeId() != null
            ? keepBus.routeId()
            : routeAnalysisService.gbisRouteIds(bus).iterator().next();

        return routeStationService
            .routeStations(routeId, context.store().budget(), context.now())
            .stream()
            .filter(station -> station.stationId().equals(alight))
            .findFirst()
            .map(station -> routeStationService.sameStations(
                station, context.store().budget(), context.now()
            ))
            .orElse(List.of(alight));
    }

    /**
     * 대체 노선 탑승 시간(분).
     * 다른 승강장(alightId가 원래 하차 정류장이 아님)인데 그 노선이 승차 → 하차 순서로
     * 지나는지 확인 못 하면 null (반대 방향 승강장일 수 있음).
     */
    private Integer altRideMin(
        Context context,
        RouteSegment bus,
        String routeId,
        String alightId
    ) {
        // 다른 후보 경로에 같은 구간·노선이 있으면 경로 API 시간
        for (RouteInput route : context.alternativeRoutes()) {
            for (RouteSegment segment : route.segments()) {
                if (BUS.equals(segment.mode())
                    && segment.durationMin() != null
                    && Objects.equals(segment.startLocalStationId(), bus.startLocalStationId())
                    && Objects.equals(segment.endLocalStationId(), alightId)
                    && segment.busOptions() != null
                    && segment.busOptions().stream().anyMatch(option ->
                        option != null && routeId.equals(option.localRouteId()))) {
                    return segment.durationMin();
                }
            }
        }

        int originalMin = minutes(bus);
        List<RouteStation> stations = routeStationService.routeStations(
            routeId, context.store().budget(), context.now()
        );
        Integer boardSeq = RouteStationService.boardSeq(
            stations, bus.startLocalStationId(), alightId
        );

        Integer alightSeq = boardSeq == null
            ? null
            : stations.stream()
                .filter(station -> station.stationId().equals(alightId))
                .map(RouteStation::seq)
                .filter(seq -> seq > boardSeq)
                .min(Integer::compare)
                .orElse(null);

        if (alightSeq == null) {
            return alightId.equals(bus.endLocalStationId()) ? originalMin : null;
        }

        return Math.max((alightSeq - boardSeq) * MIN_PER_STATION, originalMin);
    }

    /**
     * 거슬러타기: 같은 노선의 이전 정류장 최대 5곳(가까운 순)으로 걸어가 탄다.
     * 첫 버스 앞이 도보뿐일 때만 만든다 (현재 위치에서 걸어가므로).
     * 탑승 시간은 같은 차량이 이전 정류장 → 원래 정류장까지 걸린 시간을 더하고,
     * 같은 차량이 없으면 정류장 수 × 2분을 더한다.
     */
    private List<Candidate> backtracks(Context context, int busIndex) {
        List<Candidate> result = new ArrayList<>();
        Location location = context.currentLocation();
        List<RouteSegment> segments = context.route().segments();

        if (location == null || location.x() == null || location.y() == null) {
            return result;
        }

        for (int i = 0; i < busIndex; i++) {
            if (!WALK.equals(segments.get(i).mode())) {
                return result;
            }
        }

        RouteSegment bus = segments.get(busIndex);
        SegmentResult keepBus = context.keepPlan().segments().get(busIndex).result();
        String routeId = keepBus.routeId() != null
            ? keepBus.routeId()
            : routeAnalysisService.gbisRouteIds(bus).iterator().next();
        String label = keepBus.routeLabel() != null
            ? keepBus.routeLabel()
            : busNo(bus, routeId);

        List<RouteStation> stations = routeStationService.routeStations(
            routeId, context.store().budget(), context.now()
        );
        Integer boardSeq = RouteStationService.boardSeq(
            stations, bus.startLocalStationId(), bus.endLocalStationId()
        );

        // 승차 정류장이 노선 목록에 없으면 건너뛴다
        if (boardSeq == null) {
            return result;
        }

        // 직선거리로도 15분이 넘는 정류장은 도보 API를 부르지 않고 버린다
        List<RouteStation> nearby = new ArrayList<>();

        for (int seq = boardSeq - 1; seq >= boardSeq - MAX_PER_TYPE && seq >= 1; seq--) {
            RouteStation station = stationAt(stations, seq);

            if (station != null
                && station.x() != null
                && station.y() != null
                && straightMeters(location, station.x(), station.y())
                    / WALK_METERS_PER_MIN <= MAX_WALK_MIN) {
                nearby.add(station);
            }
        }

        List<Integer> walkMins = walkMins(location, nearby);
        int originalWalkMin = leadingWalkMin(context.route());

        for (int i = 0; i < nearby.size(); i++) {
            RouteStation station = nearby.get(i);
            int walkMin = walkMins.get(i);

            if (walkMin > MAX_WALK_MIN) {
                continue;
            }

            context.store().load(station.stationId(), Set.of(routeId));
            conservativeSeats(context, station.stationId(), bus.startLocalStationId());

            int rideMin = minutes(bus) + extraRideMin(
                context, station.stationId(), bus.startLocalStationId(),
                routeId, boardSeq - station.seq()
            );

            List<RouteSegment> changed = new ArrayList<>();
            changed.add(new RouteSegment(
                segments.get(0).segmentIndex(),
                WALK, walkMin, null, station.name(), null, null, null, List.of()
            ));
            changed.add(new RouteSegment(
                bus.segmentIndex(), BUS, rideMin,
                station.name(), bus.endName(),
                station.stationId(), bus.endLocalStationId(), null,
                List.of(new BusOption(label, routeId))
            ));
            changed.addAll(segments.subList(busIndex + 1, segments.size()));

            evaluate(
                context,
                StrategyType.BACKTRACK,
                "이전 정류장에서 " + label + "번 타기",
                withSegments(context.route(), changed),
                1,
                station.name(), station.x(), station.y(),
                Math.max(0, walkMin - originalWalkMin)
            ).ifPresent(result::add);
        }

        return result;
    }

    /**
     * 현재 위치 → 정류장 도보 시간(카카오 도보 API). 정류장마다 동시에 부르고 2초까지 기다린다.
     * 실패하거나 늦으면 그 정류장만 직선거리 × 1.3 ÷ 67m/분으로 대신 계산한다.
     */
    private List<Integer> walkMins(Location from, List<RouteStation> stations) {
        List<CompletableFuture<Integer>> futures = stations.stream()
            .map(station -> CompletableFuture
                .supplyAsync(() -> kakaoWalkMin(from, station))
                .completeOnTimeout(null, WALK_API_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(e -> null))
            .toList();

        List<Integer> result = new ArrayList<>();

        for (int i = 0; i < stations.size(); i++) {
            Integer walkMin = futures.get(i).join();
            RouteStation station = stations.get(i);

            result.add(walkMin != null
                ? walkMin
                : walkMin(from, station.x(), station.y()));
        }

        return result;
    }

    /**
     * 현재 위치에서 노선의 정류장까지 걷는 시간(분). 재평가 이동 중 남은 도보에 쓴다.
     * 정류장 좌표는 저장된 노선 정보에서만 찾고(GBIS 호출 없음), 도보 API가 1초 안에 안 오면 직선거리로 어림한다.
     * 정류장 좌표를 못 찾으면 null.
     */
    public Integer walkMinToStation(
        Location from,
        String routeId,
        String stationId,
        OffsetDateTime now
    ) {
        RouteStation station = routeStationService
            .routeStations(routeId, CallBudget.none(), now)
            .stream()
            .filter(s -> stationId.equals(s.stationId()))
            .filter(s -> s.x() != null && s.y() != null)
            .findFirst()
            .orElse(null);

        if (station == null) {
            return null;
        }

        Integer walkMin = CompletableFuture
            .supplyAsync(() -> kakaoWalkMin(from, station))
            .completeOnTimeout(null, CURRENT_WALK_API_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .exceptionally(e -> null)
            .join();

        return walkMin != null
            ? walkMin
            : walkMin(from, station.x(), station.y());
    }

    private Integer kakaoWalkMin(Location from, RouteStation station) {
        WalkingRouteResponse response = walkingRouteService.search(
            from.x(), from.y(), station.x(), station.y(), WALK_ROUTE_MODE
        );

        return response == null
            ? null
            : (int) Math.ceil(response.totalTimeSeconds() / 60.0);
    }

    /**
     * 이전 정류장이 예측 대상이 아니라 지금 좌석만 있으면,
     * 같은 차량의 원래 정류장 예측 좌석과 비교해 작은 값을 쓴다 (이전 정류장이 더 좋아 보이지 않게).
     */
    private void conservativeSeats(
        Context context,
        String stationId,
        String originalStationId
    ) {
        StationArrivals arrivals = context.store().arrivals().get(stationId);
        StationArrivals original = context.store().arrivals().get(originalStationId);

        if (arrivals == null || original == null) {
            return;
        }

        List<BusArrival> adjusted = arrivals.arrivals()
            .stream()
            .map(arrival -> {
                if (arrival.seatType() == SeatType.PREDICTED
                    || arrival.plateNo() == null) {
                    return arrival;
                }

                return original.arrivals()
                    .stream()
                    .filter(item -> arrival.plateNo().equals(item.plateNo())
                        && item.seatType() == SeatType.PREDICTED
                        && item.remainSeats() != null)
                    .findFirst()
                    .map(item -> new BusArrival(
                        arrival.routeId(),
                        arrival.routeLabel(),
                        arrival.plateNo(),
                        arrival.arrivalAt(),
                        arrival.remainSeats() == null
                            ? item.remainSeats()
                            : Math.min(arrival.remainSeats(), item.remainSeats()),
                        SeatType.PREDICTED,
                        arrival.dataBasisAt(),
                        item.modelVersion()
                    ))
                    .orElse(arrival);
            })
            .toList();

        context.store().replace(stationId, new StationArrivals(
            adjusted, arrivals.dataBasisAt(), arrivals.delayed()
        ));
    }

    /**
     * 이전 정류장 → 원래 정류장 버스 이동 시간.
     * 두 정류장 도착 목록에 같이 나오는 차량(차량번호)이 있으면 그 도착 시각 차이,
     * 없으면 정류장 수 × 2분.
     */
    private int extraRideMin(
        Context context,
        String stationId,
        String originalStationId,
        String routeId,
        int stationCount
    ) {
        StationArrivals before = context.store().arrivals().get(stationId);
        StationArrivals original = context.store().arrivals().get(originalStationId);

        if (before != null && original != null) {
            for (BusArrival early : before.arrivals()) {
                if (!routeId.equals(early.routeId()) || early.plateNo() == null) {
                    continue;
                }

                for (BusArrival late : original.arrivals()) {
                    if (early.plateNo().equals(late.plateNo())
                        && !early.arrivalAt().isAfter(late.arrivalAt())) {
                        return (int) Duration.between(
                            early.arrivalAt(),
                            late.arrivalAt()
                        ).toMinutes();
                    }
                }
            }
        }

        return stationCount * MIN_PER_STATION;
    }

    /**
     * 우회·다른 교통수단: 다른 후보 경로를 그대로 쓴다.
     * 우회는 첫 대중교통이 MATCHED 버스이고 승차 정류장이 다른 경로, 첫 도보 15분 이내.
     * 다른 교통수단은 버스가 없는 경로로, 실시간 조회 없이 경로 API 시간만 쓴다.
     */
    private List<Candidate> otherRoutes(Context context, int busIndex) {
        List<Candidate> detours = new ArrayList<>();
        List<Candidate> otherModes = new ArrayList<>();
        String currentBoard =
            context.route().segments().get(busIndex).startLocalStationId();
        int originalWalkMin = leadingWalkMin(context.route());

        for (RouteInput route : context.alternativeRoutes()) {
            List<RouteSegment> segments = route.segments();

            if (segments.isEmpty()) {
                continue;
            }

            int walkMin = leadingWalkMin(route);
            int extraWalkMin = Math.max(0, walkMin - originalWalkMin);
            boolean hasBus = segments.stream()
                .anyMatch(segment -> BUS.equals(segment.mode()));

            if (!hasBus) {
                if (otherModes.size() < MAX_PER_TYPE) {
                    boolean subway = segments.stream()
                        .anyMatch(segment -> SUBWAY.equals(segment.mode()));

                    evaluate(
                        context,
                        StrategyType.OTHER_MODE,
                        subway ? "지하철로 가기" : "다른 경로로 가기",
                        route,
                        -1,
                        null, null, null,
                        extraWalkMin
                    ).ifPresent(otherModes::add);
                }
                continue;
            }

            int first = firstTransitIndex(route);
            RouteSegment bus = segments.get(first);

            if (detours.size() >= MAX_PER_TYPE
                || !BUS.equals(bus.mode())
                || !"MATCHED".equals(routeAnalysisService.matchStatus(bus))
                || Objects.equals(bus.startLocalStationId(), currentBoard)
                || walkMin > MAX_WALK_MIN) {
                continue;
            }

            context.store().load(route);

            evaluate(
                context,
                StrategyType.DETOUR,
                null,
                route,
                first,
                bus.startName(), null, null,
                extraWalkMin
            ).ifPresent(detours::add);
        }

        List<Candidate> result = new ArrayList<>();

        for (Candidate detour : detours) {
            result.add(withStationLocation(context, detour));
        }

        result.addAll(otherModes);
        return result;
    }

    // 우회 정류장 좌표: 그 노선의 경유 정류장 목록(1일 캐시)에서 찾는다. 못 찾으면 null
    private Candidate withStationLocation(Context context, Candidate candidate) {
        SegmentResult bus = candidate.boardPlan().firstBus();
        RouteSegment segment = candidate.boardPlan()
            .segments()
            .get(candidate.changedIndex())
            .segment();

        RouteStation station = routeStationService
            .routeStations(bus.routeId(), context.store().budget(), context.now())
            .stream()
            .filter(item -> item.stationId().equals(segment.startLocalStationId()))
            .findFirst()
            .orElse(null);

        return new Candidate(
            candidate.type(),
            segment.startName() + "에서 " + bus.routeLabel() + "번 타기",
            candidate.route(),
            candidate.changedIndex(),
            candidate.moveToName(),
            station == null ? null : station.x(),
            station == null ? null : station.y(),
            candidate.extraWalkMin(),
            candidate.boardPlan(),
            candidate.missPlan()
        );
    }

    /**
     * 후보 경로를 경로 따라가기로 계산한다.
     * 바꾼 버스 구간(changedIndex)의 도착 시각을 모르거나 여유시간이 위험이면 버린다.
     * changedIndex가 -1이면(다른 교통수단) 확인하지 않는다.
     * 사용자가 고친 지하철 하차 시각은 고른 경로의 구간이라, 고른 경로를 바꾼 후보
     * (대체 노선·거슬러타기)에만 쓴다. 다른 후보 경로(우회·다른 교통수단)는 구간 번호가
     * 같아도 다른 지하철일 수 있어 쓰지 않는다.
     */
    private Optional<Candidate> evaluate(
        Context context,
        StrategyType type,
        String title,
        RouteInput route,
        int changedIndex,
        String moveToName,
        Double moveToX,
        Double moveToY,
        int extraWalkMin
    ) {
        if (changedIndex >= 0) {
            context.store().load(route);
        }

        Map<String, StationArrivals> arrivals = context.store().arrivals();
        Map<Integer, OffsetDateTime> alightAt =
            type == StrategyType.DETOUR || type == StrategyType.OTHER_MODE
                ? Map.of()
                : context.alightAtBySegment();

        RoutePlan boardPlan = routeAnalysisService.plan(
            route, arrivals, alightAt,
            false, context.baseTime(), context.now()
        );

        if (changedIndex >= 0) {
            SegmentResult changed = boardPlan.segments().get(changedIndex).result();

            if (changed == null
                || changed.predictedBusArrivalAt() == null
                || changed.slackStage() == Stage.RISK) {
                return Optional.empty();
            }
        }

        RoutePlan missPlan = routeAnalysisService.plan(
            route, arrivals, alightAt,
            true, context.baseTime(), context.now()
        );

        return Optional.of(new Candidate(
            type, title, route, changedIndex,
            moveToName, moveToX, moveToY,
            extraWalkMin, boardPlan, missPlan
        ));
    }

    private static int firstMatchedIndex(RoutePlan plan) {
        for (int i = 0; i < plan.segments().size(); i++) {
            if (plan.segments().get(i).result() != null) {
                return i;
            }
        }

        return -1;
    }

    private static int firstTransitIndex(RouteInput route) {
        for (int i = 0; i < route.segments().size(); i++) {
            if (!WALK.equals(route.segments().get(i).mode())) {
                return i;
            }
        }

        return -1;
    }

    // 첫 대중교통 전까지의 도보 시간 합
    private static int leadingWalkMin(RouteInput route) {
        int total = 0;

        for (RouteSegment segment : route.segments()) {
            if (!WALK.equals(segment.mode())) {
                break;
            }
            total += minutes(segment);
        }

        return total;
    }

    // 직선거리 × 1.3 ÷ 67m/분, 올림 (도보 API 실패 시 대신 사용)
    static int walkMin(Location from, double toX, double toY) {
        return (int) Math.ceil(
            straightMeters(from, toX, toY) * WALK_DETOUR_RATIO / WALK_METERS_PER_MIN
        );
    }

    private static double straightMeters(Location from, double toX, double toY) {
        double lat1 = Math.toRadians(from.y());
        double lat2 = Math.toRadians(toY);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(toX - from.x());

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a));
    }

    private static RouteStation stationAt(List<RouteStation> stations, int seq) {
        return stations.stream()
            .filter(station -> station.seq() == seq)
            .findFirst()
            .orElse(null);
    }

    private static String busNo(RouteSegment bus, String routeId) {
        return bus.busOptions()
            .stream()
            .filter(option -> option != null && routeId.equals(option.localRouteId()))
            .map(BusOption::busNo)
            .findFirst()
            .orElse(null);
    }

    /**
     * 바꾼 구간만큼 총 시간도 바꾼다.
     * 지하철 환승 대기(총 시간 − 구간 합)가 원래 경로와 같게 남도록.
     */
    private static RouteInput withSegments(
        RouteInput route,
        List<RouteSegment> segments
    ) {
        Integer totalTimeMin = route.totalTimeMin() == null
            ? null
            : route.totalTimeMin() + sum(segments) - sum(route.segments());

        return new RouteInput(route.routeIndex(), totalTimeMin, segments);
    }

    private static int sum(List<RouteSegment> segments) {
        return segments.stream().mapToInt(AlternativeService::minutes).sum();
    }

    private static int minutes(RouteSegment segment) {
        return segment.durationMin() == null ? 0 : segment.durationMin();
    }

    /**
     * 대안 생성 입력.
     * route: 고른 경로(재평가는 남은 구간), keepPlan: 그 경로의 KEEP 계산 결과
     * baseTime이 지금보다 뒤면 버스에 타 있는 것으로 본다.
     */
    record Context(
        RouteInput route,
        RoutePlan keepPlan,
        List<RouteInput> alternativeRoutes,
        Map<Integer, OffsetDateTime> alightAtBySegment,
        Location currentLocation,
        OffsetDateTime baseTime,
        OffsetDateTime now,
        ArrivalStore store
    ) {
    }

    /**
     * 전략 후보 하나. changedIndex는 바꾼(또는 새로 타는) 버스 구간 위치, 다른 교통수단은 -1.
     */
    record Candidate(
        StrategyType type,
        String title,
        RouteInput route,
        int changedIndex,
        String moveToName,
        Double moveToX,
        Double moveToY,
        int extraWalkMin,
        RoutePlan boardPlan,
        RoutePlan missPlan
    ) {
    }
}
