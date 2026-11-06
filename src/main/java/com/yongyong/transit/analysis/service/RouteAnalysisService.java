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

        // 요청 안에서 같은 정류장은 한 번만 조회 (필요한 노선을 모아서)
        Map<String, Set<String>> routeIdsByStation = new HashMap<>();

        for (RouteInput route : request.routes()) {
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
                    now
                )
            )
        );

        List<RouteAnalysis> routes = new ArrayList<>();

        for (RouteInput route : request.routes()) {
            routes.add(analyzeRoute(route, arrivalsByStation, now));
        }

        OffsetDateTime dataBasisAt = arrivalsByStation.values()
            .stream()
            .map(StationArrivals::dataBasisAt)
            .filter(time -> time != null)
            .min(Comparator.naturalOrder())
            .orElse(null);

        boolean dataDelayed = arrivalsByStation.values()
            .stream()
            .anyMatch(StationArrivals::delayed)
            || (dataBasisAt != null
                && Duration.between(dataBasisAt, now).getSeconds()
                    >= DELAYED_SECONDS);

        return new AnalysisResponse(
            now,
            seconds(dataBasisAt),
            dataDelayed,
            routes
        );
    }

    private RouteAnalysis analyzeRoute(
        RouteInput route,
        Map<String, StationArrivals> arrivalsByStation,
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

        // 버스 구간이 없는 경로
        if (lastBusIndex < 0) {
            return new RouteAnalysis(
                route.routeIndex(),
                true,
                null,
                route.totalTimeMin(),
                List.of()
            );
        }

        // 버스 구간이 모두 FAILED면 분석 불가
        if (!anyMatched) {
            return new RouteAnalysis(
                route.routeIndex(),
                false,
                null,
                route.totalTimeMin(),
                List.of()
            );
        }

        List<SegmentView> views = new ArrayList<>();
        Stage routeStage = null;

        // 직전 기준점: 처음은 현재 시각, 이후는 앞 버스 하차 예정 시각
        OffsetDateTime anchorAt = now;
        int anchorIndex = -1;

        for (int i = 0; i < segments.size(); i++) {
            RouteSegment segment = segments.get(i);

            if (!BUS.equals(segment.mode())) {
                continue;
            }

            boolean walkBefore = i > 0
                && WALK.equals(segments.get(i - 1).mode());

            int walkMin = walkBefore
                ? minutes(segments.get(i - 1))
                : 0;

            int betweenMin = 0;
            int betweenEnd = walkBefore ? i - 1 : i;

            for (int j = anchorIndex + 1; j < betweenEnd; j++) {
                betweenMin += minutes(segments.get(j));
            }

            int afterMin = 0;

            for (int j = i + 1; j < segments.size(); j++) {
                afterMin += minutes(segments.get(j));
            }

            if (!MATCHED.equals(matchStatus(segment))) {
                views.add(SegmentView.failed(
                    segment.segmentIndex(),
                    firstBusNo(segment.busOptions()),
                    segment.startName()
                ));
                continue;
            }

            Set<String> routeIds = gbisRouteIds(segment);

            List<BusArrival> arrivals = arrivalsByStation
                .get(segment.startLocalStationId())
                .arrivals()
                .stream()
                .filter(arrival -> routeIds.contains(arrival.routeId()))
                .toList();

            SegmentResult result = segmentAnalyzer.analyze(
                new SegmentAnalyzer.SegmentInput(
                    segment.segmentIndex(),
                    gbisBusNo(segment),
                    segment.startName(),
                    anchorAt.plusMinutes(betweenMin),
                    walkMin,
                    minutes(segment),
                    afterMin,
                    i == lastBusIndex,
                    arrivals,
                    now
                )
            );

            views.add(SegmentView.of(result));

            routeStage = routeStage == null
                ? result.boardingStage()
                : Stage.worse(routeStage, result.boardingStage());

            // 앞 버스 하차 예정 시각을 다음 버스의 기준점으로.
            // 도착 정보가 없으면 기준점을 그대로 두고 ODsay 소요시간으로 이어서 계산
            if (result.predictedBusArrivalAt() != null) {
                anchorAt = result.predictedBusArrivalAt()
                    .plusMinutes(minutes(segment));
                anchorIndex = i;
            }
        }

        return new RouteAnalysis(
            route.routeIndex(),
            true,
            routeStage,
            route.totalTimeMin(),
            views
        );
    }

    private void validate(AnalysisRequest request) {
        if (request == null
            || request.routes() == null
            || request.routes().isEmpty()
            || request.routes().stream().anyMatch(route ->
                route == null || route.segments() == null)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "후보 경로 정보가 올바르지 않습니다."
            );
        }
    }

    /**
     * 요청에 matchStatus가 있으면 그대로 쓰고, 없으면 직접 판단한다.
     * 버스가 아니면 NA, 승차 정류장 ID가 있고 경기 노선(ID가 2로 시작)이 있으면 MATCHED, 그 외 FAILED.
     */
    private String matchStatus(RouteSegment segment) {
        if (!BUS.equals(segment.mode())) {
            return NA;
        }

        if (segment.matchStatus() != null
            && !segment.matchStatus().isBlank()) {
            return MATCHED.equals(segment.matchStatus()) ? MATCHED : FAILED;
        }

        boolean hasStation = segment.startLocalStationId() != null
            && !segment.startLocalStationId().isBlank();

        return hasStation && !gbisRouteIds(segment).isEmpty()
            ? MATCHED
            : FAILED;
    }

    private Set<String> gbisRouteIds(RouteSegment segment) {
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

    private String firstBusNo(List<BusOption> busOptions) {
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

    private static OffsetDateTime seconds(OffsetDateTime time) {
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
        String startLocalStationId,
        String matchStatus,
        List<BusOption> busOptions
    ) {
    }

    public record BusOption(
        String busNo,
        String localRouteId
    ) {
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
        static SegmentView of(SegmentResult result) {
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

        // GBIS로 조회할 수 없는 버스 구간: 실시간 값은 모두 null
        static SegmentView failed(
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
