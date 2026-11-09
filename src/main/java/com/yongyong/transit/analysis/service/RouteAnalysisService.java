package com.yongyong.transit.analysis.service;

import com.yongyong.transit.analysis.service.BusRealtimeService.CollectorSnapshot;
import com.yongyong.transit.analysis.service.BusRealtimeService.StationArrivals;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.BusArrival;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SeatType;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SegmentResult;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.Stage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 후보 경로 위험 분석.
 * routes/search 결과의 버스 구간마다 실시간 조회와 구간 분석을 하고,
 * 경로 단계는 버스 구간 중 가장 위험한 단계로 정한다.
 */
@Service
public class RouteAnalysisService {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final long DELAYED_SECONDS = 240;

    private static final String BUS = "BUS";
    private static final String WALK = "WALK";
    private static final String SUBWAY = "SUBWAY";
    private static final String MATCHED = "MATCHED";
    private static final String FAILED = "FAILED";
    private static final String NA = "NA";

    private final BusRealtimeService busRealtimeService;
    private final SegmentAnalyzer segmentAnalyzer;

    public RouteAnalysisService(
        BusRealtimeService busRealtimeService,
        SegmentAnalyzer segmentAnalyzer
    ) {
        this.busRealtimeService = busRealtimeService;
        this.segmentAnalyzer = segmentAnalyzer;
    }

    public AnalysisResponse analyze(AnalysisRequest request) {
        return analyze(
            request,
            OffsetDateTime.now(KST).truncatedTo(ChronoUnit.SECONDS)
        );
    }

    AnalysisResponse analyze(
        AnalysisRequest request,
        OffsetDateTime now
    ) {
        validate(request);

        Map<String, StationArrivals> arrivalsByStation = loadArrivals(
            request.routes(), now, CallBudget.unlimited()
        );

        List<RouteAnalysis> routes = new ArrayList<>();

        for (RouteInput route : request.routes()) {
            routes.add(analyzeRoute(route, arrivalsByStation, now));
        }

        OffsetDateTime dataBasisAt = dataBasisAt(arrivalsByStation);

        return new AnalysisResponse(
            now,
            seconds(dataBasisAt),
            dataDelayed(arrivalsByStation, now),
            routes
        );
    }

    /**
     * 여러 경로의 MATCHED 버스 정류장을 모아 정류장마다 한 번씩 실시간 조회한다.
     * 수집 서버는 조회할 정류장이 있을 때만 한 번 부른다.
     */
    public Map<String, StationArrivals> loadArrivals(
        List<RouteInput> routes,
        OffsetDateTime now,
        CallBudget budget
    ) {
        Map<String, Set<String>> routeIdsByStation = new HashMap<>();

        for (RouteInput route : routes) {
            for (RouteSegment segment : route.segments()) {
                if (MATCHED.equals(matchStatus(segment))) {
                    routeIdsByStation
                        .computeIfAbsent(
                            segment.startLocalStationId(),
                            key -> new HashSet<>()
                        )
                        .addAll(gbisRouteIds(segment));
                }
            }
        }

        CollectorSnapshot snapshot = routeIdsByStation.isEmpty()
            ? CollectorSnapshot.empty()
            : busRealtimeService.loadCollectorSnapshot(now);

        Map<String, StationArrivals> arrivalsByStation = new HashMap<>();

        routeIdsByStation.forEach((stationId, routeIds) ->
            arrivalsByStation.put(
                stationId,
                busRealtimeService.getArrivals(
                    stationId,
                    routeIds,
                    snapshot,
                    now,
                    budget
                )
            )
        );

        return arrivalsByStation;
    }

    // 쓴 데이터 중 가장 오래된 기준 시각. 없으면 null
    public static OffsetDateTime dataBasisAt(
        Map<String, StationArrivals> arrivalsByStation
    ) {
        return arrivalsByStation.values()
            .stream()
            .map(StationArrivals::dataBasisAt)
            .filter(Objects::nonNull)
            .min(Comparator.naturalOrder())
            .orElse(null);
    }

    // 하나라도 지연(캐시 사용) 표시가 있거나 4분 이상 지났으면 true
    public static boolean dataDelayed(
        Map<String, StationArrivals> arrivalsByStation,
        OffsetDateTime now
    ) {
        OffsetDateTime basis = dataBasisAt(arrivalsByStation);

        return arrivalsByStation.values()
            .stream()
            .anyMatch(StationArrivals::delayed)
            || (basis != null
                && Duration.between(basis, now).getSeconds()
                    >= DELAYED_SECONDS);
    }

    public RouteAnalysis analyzeRoute(
        RouteInput route,
        Map<String, StationArrivals> arrivalsByStation,
        OffsetDateTime now
    ) {
        return toAnalysis(
            route,
            plan(route, arrivalsByStation, Map.of(), false, now, now)
        );
    }

    // 경로 따라가기 결과를 2번 응답의 routes[] 모양으로 바꾼다
    public RouteAnalysis toAnalysis(
        RouteInput route,
        RoutePlan plan
    ) {
        // 버스 구간이 없는 경로: 분석하지 않고 경로 정보만
        if (!plan.hasBus()) {
            return new RouteAnalysis(
                route.routeIndex(), true, null, route.totalTimeMin(), List.of()
            );
        }

        // 버스 구간이 모두 FAILED면 분석 불가
        if (!plan.anyMatched()) {
            return new RouteAnalysis(
                route.routeIndex(), false, null, route.totalTimeMin(), List.of()
            );
        }

        List<SegmentView> views = plan.segments()
            .stream()
            .filter(PlannedSegment::isBus)
            .map(PlannedSegment::view)
            .toList();

        return new RouteAnalysis(
            route.routeIndex(),
            true,
            plan.stage(),
            route.totalTimeMin(),
            views
        );
    }

    /**
     * 경로 따라가기: 시각 t를 들고 구간을 처음부터 끝까지 지나가며 계산한다.
     * - 도보·지하철: t += durationMin (지하철은 사용자 하차 시각이 있으면 t = 그 시각)
     * - MATCHED 버스: 구간 분석으로 t 이후 첫 버스를 골라 타고 t = 도착 + durationMin.
     *   뒤 버스도 앞 버스 하차 시각 기준으로 다시 고르므로 환승 대기·연쇄 지연이 들어간다.
     *   도착 정보가 없으면 t += durationMin (경로 API 시간으로 이어서 계산)
     * - FAILED 버스: t += durationMin
     * missFirstBus면 첫 MATCHED 버스는 다음 버스를 탄다. 다음 버스가 없으면 arrivalAt은 null.
     * 지하철끼리 갈아타면 그 지하철을 타기 전에 환승 대기를 기다린다 (transferWaits).
     * 재평가는 남은 구간만 넣고 startAt(기준 시각)을 지정해 그대로 쓸 수 있다.
     * 이때 totalTimeMin은 null로 넣는다 (남은 구간과 맞지 않아 대기가 잘못 계산된다).
     */
    public RoutePlan plan(
        RouteInput route,
        Map<String, StationArrivals> arrivalsByStation,
        Map<Integer, OffsetDateTime> alightAtBySegment,
        boolean missFirstBus,
        OffsetDateTime startAt,
        OffsetDateTime now
    ) {
        List<RouteSegment> segments = route.segments();

        int lastBusIndex = -1;
        boolean anyMatched = false;

        for (int i = 0; i < segments.size(); i++) {
            if (BUS.equals(segments.get(i).mode())) {
                lastBusIndex = i;
                anyMatched |= MATCHED.equals(matchStatus(segments.get(i)));
            }
        }

        Map<Integer, Integer> waits = transferWaits(route);
        List<PlannedSegment> planned = new ArrayList<>();
        Stage routeStage = null;
        boolean firstMatchedPassed = false;
        boolean missImpossible = false;
        OffsetDateTime t = startAt;

        for (int i = 0; i < segments.size(); i++) {
            RouteSegment segment = segments.get(i);
            String status = matchStatus(segment);
            int durationMin = minutes(segment);
            t = t.plusMinutes(waits.getOrDefault(i, 0));

            if (!MATCHED.equals(status)) {
                OffsetDateTime end = t.plusMinutes(durationMin);
                OffsetDateTime userAlightAt = SUBWAY.equals(segment.mode())
                    ? alightAtBySegment.get(segment.segmentIndex())
                    : null;

                if (userAlightAt != null) {
                    end = userAlightAt;
                }

                planned.add(new PlannedSegment(
                    segment, status, t, end, null, userAlightAt
                ));
                t = end;
                continue;
            }

            int afterMin = 0;

            for (int j = i + 1; j < segments.size(); j++) {
                afterMin += minutes(segments.get(j)) + waits.getOrDefault(j, 0);
            }

            Set<String> routeIds = gbisRouteIds(segment);
            StationArrivals stationArrivals =
                arrivalsByStation.get(segment.startLocalStationId());

            List<BusArrival> arrivals = stationArrivals == null
                ? List.of()
                : stationArrivals.arrivals()
                    .stream()
                    .filter(arrival -> routeIds.contains(arrival.routeId()))
                    .toList();

            // 앞 도보는 이미 t에 들어 있으므로 walkMin 0, 기준 시각 t
            SegmentResult result = segmentAnalyzer.analyze(
                new SegmentAnalyzer.SegmentInput(
                    segment.segmentIndex(),
                    gbisBusNo(segment),
                    segment.startName(),
                    t,
                    0,
                    durationMin,
                    afterMin,
                    i == lastBusIndex,
                    arrivals,
                    now
                )
            );

            routeStage = routeStage == null
                ? result.boardingStage()
                : Stage.worse(routeStage, result.boardingStage());

            OffsetDateTime boardAt = result.predictedBusArrivalAt();

            if (missFirstBus && !firstMatchedPassed) {
                boardAt = result.nextBusArrivalAt();
                missImpossible = boardAt == null;
            }

            firstMatchedPassed = true;

            OffsetDateTime start = boardAt != null ? boardAt : t;
            OffsetDateTime end = start.plusMinutes(durationMin);

            planned.add(new PlannedSegment(
                segment, status, start, end, result, null
            ));
            t = end;
        }

        return new RoutePlan(
            planned,
            missImpossible ? null : t,
            routeStage,
            lastBusIndex >= 0,
            anyMatched
        );
    }

    /**
     * 지하철 환승 대기: 구간 번호(리스트 순서) → 그 지하철을 타기 전 기다리는 분.
     * ODsay는 지하철끼리 갈아탈 때 기다리는 시간을 totalTimeMin에만 넣고 구간에는 넣지 않는다.
     * 빠진 시간(totalTimeMin − 구간 합)을 지하철끼리 갈아타는 곳에 똑같이 나누고,
     * 나머지는 앞쪽부터 1분씩 준다. 총 시간이 없거나 빠진 시간이 없으면 비어 있다.
     */
    Map<Integer, Integer> transferWaits(RouteInput route) {
        if (route.totalTimeMin() == null) {
            return Map.of();
        }

        List<RouteSegment> segments = route.segments();
        List<Integer> transfers = new ArrayList<>();
        String previousTransit = null;
        int sum = 0;

        for (int i = 0; i < segments.size(); i++) {
            RouteSegment segment = segments.get(i);
            sum += minutes(segment);

            if (WALK.equals(segment.mode())) {
                continue;
            }

            if (SUBWAY.equals(segment.mode()) && SUBWAY.equals(previousTransit)) {
                transfers.add(i);
            }

            previousTransit = segment.mode();
        }

        int missing = route.totalTimeMin() - sum;

        if (missing <= 0 || transfers.isEmpty()) {
            return Map.of();
        }

        Map<Integer, Integer> result = new HashMap<>();

        for (int k = 0; k < transfers.size(); k++) {
            int wait = missing / transfers.size()
                + (k < missing % transfers.size() ? 1 : 0);
            result.put(transfers.get(k), wait);
        }

        return result;
    }

    private void validate(AnalysisRequest request) {
        if (request == null
            || request.routes() == null
            || request.routes().isEmpty()
            || request.routes().stream().anyMatch(route ->
                route == null
                    || route.segments() == null
                    || route.segments().stream().anyMatch(Objects::isNull))) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "후보 경로 정보가 올바르지 않습니다."
            );
        }
    }

    /**
     * 요청에 matchStatus가 있으면 우선하고, 없으면 직접 판단한다.
     * 버스가 아니면 NA, 승차 정류장 ID가 있고 경기 노선(ID가 2로 시작)이 있으면 MATCHED, 그 외 FAILED.
     */
    public String matchStatus(RouteSegment segment) {
        if (!BUS.equals(segment.mode())) {
            return NA;
        }

        // 요청이 FAILED면 따르고, MATCHED여도 조회에 필요한 값이 있을 때만 인정
        if (segment.matchStatus() != null
            && !segment.matchStatus().isBlank()
            && !MATCHED.equals(segment.matchStatus())) {
            return FAILED;
        }

        boolean hasStation = segment.startLocalStationId() != null
            && !segment.startLocalStationId().isBlank();

        return hasStation && !gbisRouteIds(segment).isEmpty()
            ? MATCHED
            : FAILED;
    }

    public Set<String> gbisRouteIds(RouteSegment segment) {
        Set<String> routeIds = new LinkedHashSet<>();

        if (segment.busOptions() == null) {
            return routeIds;
        }

        for (BusOption option : segment.busOptions()) {
            if (isGbisRoute(option)) {
                routeIds.add(option.localRouteId());
            }
        }

        return routeIds;
    }

    private String gbisBusNo(RouteSegment segment) {
        return segment.busOptions()
            .stream()
            .filter(this::isGbisRoute)
            .map(BusOption::busNo)
            .findFirst()
            .orElse(null);
    }

    private static String firstBusNo(List<BusOption> busOptions) {
        if (busOptions == null || busOptions.isEmpty()) {
            return null;
        }

        return busOptions.get(0).busNo();
    }

    private boolean isGbisRoute(BusOption option) {
        return option != null
            && option.localRouteId() != null
            && option.localRouteId().startsWith("2");
    }

    private int minutes(RouteSegment segment) {
        return segment.durationMin() == null ? 0 : segment.durationMin();
    }

    public static OffsetDateTime seconds(OffsetDateTime time) {
        return time == null ? null : time.truncatedTo(ChronoUnit.SECONDS);
    }

    public record AnalysisRequest(
        List<RouteInput> routes
    ) {
    }

    public record RouteInput(
        Integer routeIndex,
        Integer totalTimeMin,
        List<RouteSegment> segments
    ) {
    }

    public record RouteSegment(
        Integer segmentIndex,
        String mode,
        Integer durationMin,
        String startName,
        String endName,
        String startLocalStationId,
        String endLocalStationId,
        String matchStatus,
        List<BusOption> busOptions
    ) {
    }

    public record BusOption(
        String busNo,
        String localRouteId
    ) {
    }

    /**
     * 경로 따라가기 결과.
     * arrivalAt: 목적지 도착 시각 (놓친 경우 다음 버스가 없으면 null)
     * stage: 버스 구간 탑승 단계 중 가장 위험한 값 (MATCHED 버스가 없으면 null)
     */
    public record RoutePlan(
        List<PlannedSegment> segments,
        OffsetDateTime arrivalAt,
        Stage stage,
        boolean hasBus,
        boolean anyMatched
    ) {
        // 첫 MATCHED 버스 구간의 분석 결과. 없으면 null
        public SegmentResult firstBus() {
            return segments.stream()
                .map(PlannedSegment::result)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        }
    }

    /**
     * 구간 하나의 예정 시각.
     * 버스는 탈 버스 도착 시각부터 하차까지, 도착 정보가 없으면 경로 API 시간 기준.
     * result는 MATCHED 버스 구간만 있다.
     */
    public record PlannedSegment(
        RouteSegment segment,
        String matchStatus,
        OffsetDateTime plannedStartAt,
        OffsetDateTime plannedEndAt,
        SegmentResult result,
        OffsetDateTime userAlightAt
    ) {
        public boolean isBus() {
            return BUS.equals(segment.mode());
        }

        // 2번 응답 segments[] 모양. FAILED 버스는 실시간 값 없이
        public SegmentView view() {
            if (result == null) {
                return SegmentView.failed(
                    segment.segmentIndex(),
                    firstBusNo(segment.busOptions()),
                    segment.startName()
                );
            }

            return SegmentView.of(result);
        }
    }

    public record AnalysisResponse(
        OffsetDateTime evaluatedAt,
        OffsetDateTime dataBasisAt,
        boolean dataDelayed,
        List<RouteAnalysis> routes
    ) {
    }

    public record RouteAnalysis(
        Integer routeIndex,
        boolean analyzable,
        Stage strategyRiskStage,
        Integer totalTimeMin,
        List<SegmentView> segments
    ) {
    }

    // 프론트 응답용. plateNo·modelVersion은 넣지 않는다.
    public record SegmentView(
        Integer segmentIndex,
        String routeLabel,
        String boardName,
        OffsetDateTime predictedBusArrivalAt,
        Integer slackMin,
        Stage slackStage,
        Integer remainSeats,
        SeatType seatType,
        Stage seatStage,
        Stage boardingStage,
        OffsetDateTime arriveIfBoard,
        Integer totalMinIfBoard,
        OffsetDateTime arriveIfMiss,
        Integer totalMinIfMiss,
        String nextRouteLabel,
        OffsetDateTime nextBusArrivalAt,
        OffsetDateTime dataBasisAt
    ) {
        public static SegmentView of(SegmentResult result) {
            return new SegmentView(
                result.segmentIndex(),
                result.routeLabel(),
                result.boardName(),
                seconds(result.predictedBusArrivalAt()),
                result.slackMin(),
                result.slackStage(),
                result.remainSeats(),
                result.seatType(),
                result.seatStage(),
                result.boardingStage(),
                seconds(result.arriveIfBoard()),
                result.totalMinIfBoard(),
                seconds(result.arriveIfMiss()),
                result.totalMinIfMiss(),
                result.nextRouteLabel(),
                seconds(result.nextBusArrivalAt()),
                seconds(result.dataBasisAt())
            );
        }

        // 구간 번호만 바꾼 복사본 (전략은 route.segments 위치를 번호로 씀)
        public SegmentView withSegmentIndex(Integer index) {
            return new SegmentView(
                index, routeLabel, boardName, predictedBusArrivalAt,
                slackMin, slackStage, remainSeats, seatType, seatStage,
                boardingStage, arriveIfBoard, totalMinIfBoard, arriveIfMiss,
                totalMinIfMiss, nextRouteLabel, nextBusArrivalAt, dataBasisAt
            );
        }

        // GBIS로 조회할 수 없는 버스 구간: 실시간 값은 모두 null
        public static SegmentView failed(
            Integer segmentIndex,
            String routeLabel,
            String boardName
        ) {
            return new SegmentView(
                segmentIndex, routeLabel, boardName,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null
            );
        }
    }
}
