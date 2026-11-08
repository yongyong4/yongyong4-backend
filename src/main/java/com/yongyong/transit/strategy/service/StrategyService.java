package com.yongyong.transit.strategy.service;

import com.yongyong.transit.analysis.service.BusRealtimeService;
import com.yongyong.transit.analysis.service.CallBudget;
import com.yongyong.transit.analysis.service.RouteAnalysisService;
import com.yongyong.transit.analysis.service.RouteAnalysisService.BusOption;
import com.yongyong.transit.analysis.service.RouteAnalysisService.PlannedSegment;
import com.yongyong.transit.analysis.service.RouteAnalysisService.RouteAnalysis;
import com.yongyong.transit.analysis.service.RouteAnalysisService.RouteInput;
import com.yongyong.transit.analysis.service.RouteAnalysisService.RoutePlan;
import com.yongyong.transit.analysis.service.RouteAnalysisService.RouteSegment;
import com.yongyong.transit.analysis.service.RouteAnalysisService.SegmentView;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SeatType;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SegmentResult;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.Stage;
import com.yongyong.transit.strategy.service.AlternativeService.Candidate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.yongyong.transit.analysis.service.RouteAnalysisService.seconds;

/**
 * 전략 비교·추천.
 * 고른 경로를 처음부터 끝까지 따라가며 KEEP(그대로 타기) 전략을 만들고,
 * 대안 생성의 후보도 같은 방식으로 계산해 전략 카드로 만든다.
 * 재평가는 HTTP 대신 evaluate()를 REEVALUATION 모드로 바로 호출한다.
 */
@Service
public class StrategyService {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final int CALL_LIMIT_PER_REQUEST = 20;

    private static final String BUS = "BUS";
    private static final String WALK = "WALK";
    private static final String SUBWAY = "SUBWAY";

    private static final String FORMAT_ERROR = "선택한 경로 정보가 올바르지 않습니다.";
    private static final String NOT_ANALYZABLE = "분석할 수 없는 경로입니다.";
    private static final String ALIGHT_ERROR = "하차 시각이 올바르지 않습니다.";

    private final RouteAnalysisService routeAnalysisService;
    private final BusRealtimeService busRealtimeService;
    private final AlternativeService alternativeService;

    public StrategyService(
        RouteAnalysisService routeAnalysisService,
        BusRealtimeService busRealtimeService,
        AlternativeService alternativeService
    ) {
        this.routeAnalysisService = routeAnalysisService;
        this.busRealtimeService = busRealtimeService;
        this.alternativeService = alternativeService;
    }

    public StrategyResponse strategies(StrategyRequest request) {
        return strategies(
            request,
            OffsetDateTime.now(KST).truncatedTo(ChronoUnit.SECONDS)
        );
    }

    StrategyResponse strategies(StrategyRequest request, OffsetDateTime now) {
        validate(request);

        return evaluate(
            request.selectedRoute(),
            request.alternativeRoutes() == null
                ? List.of()
                : request.alternativeRoutes(),
            alightAtBySegment(request, now),
            request.currentLocation(),
            now,
            Mode.STRATEGY,
            now
        );
    }

    /**
     * 전략 계산 본체.
     * route: 남은 구간만 넣어도 된다 (재평가)
     * alightAtBySegment: 지하철 구간 번호 → 사용자 하차 시각
     * baseTime: 경로를 따라가기 시작하는 시각 (처음 요청은 지금, 버스 탑승 중이면 하차 예정 시각)
     * 두 모드 모두 실시간 조회를 하고 요청당 GBIS 20회까지 쓴다 (재평가는 근거 문장만 다름).
     */
    public StrategyResponse evaluate(
        RouteInput route,
        List<RouteInput> alternativeRoutes,
        Map<Integer, OffsetDateTime> alightAtBySegment,
        Location currentLocation,
        OffsetDateTime baseTime,
        Mode mode,
        OffsetDateTime now
    ) {
        CallBudget budget = CallBudget.of(CALL_LIMIT_PER_REQUEST);
        ArrivalStore store = new ArrivalStore(
            busRealtimeService, routeAnalysisService, now, budget
        );
        store.load(route);

        RoutePlan boardPlan = routeAnalysisService.plan(
            route, store.arrivals(), alightAtBySegment, false, baseTime, now
        );

        if (boardPlan.hasBus() && !boardPlan.anyMatched()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                NOT_ANALYZABLE
            );
        }

        RoutePlan missPlan = routeAnalysisService.plan(
            route, store.arrivals(), alightAtBySegment, true, baseTime, now
        );

        SegmentResult firstBus = boardPlan.firstBus();
        String keepTitle = firstBus == null || firstBus.routeLabel() == null
            ? "그대로 가기"
            : "그대로 " + firstBus.routeLabel() + "번 타기";

        List<StrategyView> strategies = new ArrayList<>();
        strategies.add(view(
            new Candidate(
                StrategyType.KEEP, keepTitle, route, -1,
                null, null, null, 0, boardPlan, missPlan
            ),
            now
        ));

        List<Candidate> candidates = alternativeService.generate(
            new AlternativeService.Context(
                route,
                boardPlan,
                alternativeRoutes,
                alightAtBySegment,
                currentLocation,
                baseTime,
                now,
                store
            )
        );

        for (Candidate candidate : candidates) {
            strategies.add(view(candidate, now));
        }

        strategies = rank(strategies);

        // 호출 한도에 닿아 캐시만 쓴 조회가 있으면 지연으로 표시
        return new StrategyResponse(
            seconds(RouteAnalysisService.dataBasisAt(store.arrivals())),
            RouteAnalysisService.dataDelayed(store.arrivals(), now)
                || budget.limitReached(),
            routeAnalysisService.toAnalysis(route, boardPlan),
            strategies
        );
    }

    /**
     * 전략 카드 하나. KEEP과 대안 모두 같은 방식으로 만든다.
     * 버스가 없는 경로(지하철만·다른 교통수단)는 단계 OK, 놓쳤을 때도 같은 시간.
     */
    private StrategyView view(Candidate candidate, OffsetDateTime now) {
        RoutePlan boardPlan = candidate.boardPlan();
        RoutePlan missPlan = candidate.missPlan();
        SegmentResult firstBus = boardPlan.firstBus();

        Stage stage = boardPlan.hasBus()
            ? riskStage(boardPlan.stage())
            : Stage.OK;

        Integer totalIfBoard = totalMin(now, boardPlan.arrivalAt());
        Integer totalIfMiss = boardPlan.hasBus()
            ? totalMin(now, missPlan.arrivalAt())
            : totalIfBoard;

        return new StrategyView(
            false,
            candidate.type() == StrategyType.KEEP,
            candidate.type(),
            candidate.title(),
            candidate.moveToName(),
            candidate.moveToX(),
            candidate.moveToY(),
            candidate.extraWalkMin(),
            stage,
            totalIfBoard,
            totalIfMiss,
            comparisonValueMin(stage, totalIfBoard, totalIfMiss),
            seconds(boardPlan.arrivalAt()),
            firstBus == null ? null : firstBus.remainSeats(),
            firstBus == null ? null : firstBus.seatType(),
            firstBus == null ? null : seconds(firstBus.predictedBusArrivalAt()),
            firstBus == null ? null : seconds(firstBus.nextBusArrivalAt()),
            null,
            List.of(),
            new TripRoute(tripSegments(boardPlan)),
            analysis(boardPlan)
        );
    }

    /**
     * 비교값: 단계가 가능이면 탔을 때 시간, 주의·위험이면 놓쳤을 때 시간.
     * 다음 버스 정보가 없어 놓쳤을 때 시간이 없으면 탔을 때 시간 (단계는 그대로).
     * 버스 없는 경로는 단계가 가능이라 경로 예상 소요가 된다.
     */
    static Integer comparisonValueMin(
        Stage stage,
        Integer totalIfBoard,
        Integer totalIfMiss
    ) {
        if (stage == Stage.OK || totalIfMiss == null) {
            return totalIfBoard;
        }

        return totalIfMiss;
    }

    /**
     * 전략 순위: 비교값 → 탔을 때 시간 → 추가 도보 순으로 작은 것, 그래도 같으면 KEEP 먼저.
     * 정렬한 뒤 종류마다 가장 앞의 1개만 남기고 (KEEP은 항상 1개), 1위를 추천한다.
     */
    static List<StrategyView> rank(List<StrategyView> strategies) {
        Comparator<Integer> nullsLast =
            Comparator.nullsLast(Comparator.naturalOrder());

        List<StrategyView> sorted = strategies.stream()
            .sorted(Comparator
                .comparing(StrategyView::comparisonValueMin, nullsLast)
                .thenComparing(StrategyView::totalMinIfBoard, nullsLast)
                .thenComparingInt(StrategyView::extraWalkMin)
                .thenComparing(strategy -> !strategy.isCurrent()))
            .toList();

        Set<StrategyType> seen = EnumSet.noneOf(StrategyType.class);
        List<StrategyView> ranked = new ArrayList<>();

        for (StrategyView strategy : sorted) {
            if (seen.add(strategy.strategyType())) {
                ranked.add(ranked.isEmpty()
                    ? strategy.withRecommended(true)
                    : strategy);
            }
        }

        return ranked;
    }

    // 정보 없음(UNKNOWN)은 주의로 본다
    private Stage riskStage(Stage stage) {
        return stage == null || stage == Stage.UNKNOWN ? Stage.CAUTION : stage;
    }

    private Integer totalMin(OffsetDateTime now, OffsetDateTime arrivalAt) {
        return arrivalAt == null
            ? null
            : (int) Duration.between(now, arrivalAt).toMinutes();
    }

    /**
     * POST /trips에 그대로 보낼 전체 구간.
     * segmentIndex는 0부터, seq는 1부터 다시 매긴다.
     * 도보 구간 이름은 앞뒤 구간에서 가져오고 처음·끝은 출발지·도착지.
     */
    private List<TripSegment> tripSegments(RoutePlan plan) {
        List<PlannedSegment> segments = plan.segments();
        List<TripSegment> result = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            PlannedSegment planned = segments.get(i);
            RouteSegment segment = planned.segment();
            boolean walk = WALK.equals(segment.mode());

            String boardName = walk
                ? (i == 0 ? "출발지" : segments.get(i - 1).segment().endName())
                : segment.startName();

            String alightName = walk
                ? (i == segments.size() - 1
                    ? "도착지"
                    : segments.get(i + 1).segment().startName())
                : segment.endName();

            result.add(new TripSegment(
                i,
                i + 1,
                segment.mode(),
                gbisRouteId(planned),
                routeLabel(planned),
                walk ? null : segment.startLocalStationId(),
                walk ? null : segment.endLocalStationId(),
                boardName,
                alightName,
                planned.matchStatus(),
                seconds(planned.plannedStartAt()),
                seconds(planned.plannedEndAt()),
                walk ? segment.durationMin() : null,
                seconds(planned.userAlightAt())
            ));
        }

        return result;
    }

    // 버스 구간 분석 결과. 구간 번호는 route.segments 위치
    private List<SegmentView> analysis(RoutePlan plan) {
        List<SegmentView> views = new ArrayList<>();

        for (int i = 0; i < plan.segments().size(); i++) {
            PlannedSegment planned = plan.segments().get(i);

            if (planned.isBus()) {
                views.add(planned.view().withSegmentIndex(i));
            }
        }

        return views;
    }

    // 실제로 탈 버스의 노선 ID. 도착 정보가 없으면 경기 노선 중 첫 번째
    private String gbisRouteId(PlannedSegment planned) {
        if (planned.result() != null && planned.result().routeId() != null) {
            return planned.result().routeId();
        }

        if (!BUS.equals(planned.segment().mode())) {
            return null;
        }

        return routeAnalysisService.gbisRouteIds(planned.segment())
            .stream()
            .findFirst()
            .orElse(null);
    }

    private String routeLabel(PlannedSegment planned) {
        if (planned.result() != null && planned.result().routeLabel() != null) {
            return planned.result().routeLabel();
        }

        List<BusOption> options = planned.segment().busOptions();

        return options == null || options.isEmpty() || options.get(0) == null
            ? null
            : options.get(0).busNo();
    }

    private void validate(StrategyRequest request) {
        if (request == null
            || !validRoute(request.selectedRoute())
            || request.selectedRoute().segments().isEmpty()
            || (request.alternativeRoutes() != null
                && !request.alternativeRoutes()
                    .stream()
                    .allMatch(this::validRoute))) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                FORMAT_ERROR
            );
        }
    }

    private boolean validRoute(RouteInput route) {
        return route != null
            && route.segments() != null
            && route.segments().stream().allMatch(segment ->
                segment != null && segment.mode() != null);
    }

    /**
     * 하차 시각 수정: 고른 경로의 지하철 구간이고, 시각이 지금 이후여야 한다.
     */
    private Map<Integer, OffsetDateTime> alightAtBySegment(
        StrategyRequest request,
        OffsetDateTime now
    ) {
        Map<Integer, OffsetDateTime> result = new HashMap<>();

        if (request.alightUpdates() == null) {
            return result;
        }

        for (AlightUpdate update : request.alightUpdates()) {
            boolean subway = update != null
                && update.segmentIndex() != null
                && request.selectedRoute()
                    .segments()
                    .stream()
                    .anyMatch(segment ->
                        Objects.equals(segment.segmentIndex(), update.segmentIndex())
                            && SUBWAY.equals(segment.mode()));

            if (!subway
                || update.userAlightAt() == null
                || update.userAlightAt().isBefore(now)) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    ALIGHT_ERROR
                );
            }

            result.put(update.segmentIndex(), update.userAlightAt());
        }

        return result;
    }

    public enum Mode {
        STRATEGY,
        REEVALUATION
    }

    public enum StrategyType {
        KEEP,
        BACKTRACK,
        DETOUR,
        ALT_ROUTE,
        OTHER_MODE
    }

    public record StrategyRequest(
        RouteInput selectedRoute,
        List<RouteInput> alternativeRoutes,
        List<AlightUpdate> alightUpdates,
        Location currentLocation
    ) {
    }

    public record AlightUpdate(
        Integer segmentIndex,
        OffsetDateTime userAlightAt
    ) {
    }

    // 현재 위치 (경도 x, 위도 y)
    public record Location(
        Double x,
        Double y
    ) {
    }

    public record StrategyResponse(
        OffsetDateTime dataBasisAt,
        boolean dataDelayed,
        RouteAnalysis currentRoute,
        List<StrategyView> strategies
    ) {
    }

    /**
     * 전략 카드 하나.
     * reasons는 근거 문장 생성에서 채운다.
     * missIntervalMin은 마을버스 배차 간격용으로 지금은 항상 null.
     */
    public record StrategyView(
        boolean recommended,
        boolean isCurrent,
        StrategyType strategyType,
        String title,
        String moveToName,
        Double moveToX,
        Double moveToY,
        int extraWalkMin,
        Stage strategyRiskStage,
        Integer totalMinIfBoard,
        Integer totalMinIfMiss,
        Integer comparisonValueMin,
        OffsetDateTime expectedArrivalAt,
        Integer remainSeats,
        SeatType seatType,
        OffsetDateTime busArrivalAt,
        OffsetDateTime nextBusArrivalAt,
        Object missIntervalMin,
        List<String> reasons,
        TripRoute route,
        List<SegmentView> analysis
    ) {
        public StrategyView withRecommended(boolean value) {
            return new StrategyView(
                value, isCurrent, strategyType, title, moveToName, moveToX,
                moveToY, extraWalkMin, strategyRiskStage, totalMinIfBoard,
                totalMinIfMiss, comparisonValueMin, expectedArrivalAt,
                remainSeats, seatType, busArrivalAt, nextBusArrivalAt,
                missIntervalMin, reasons, route, analysis
            );
        }
    }

    public record TripRoute(
        List<TripSegment> segments
    ) {
    }

    // trips selectedRoute.segments 모양 + segmentIndex
    public record TripSegment(
        int segmentIndex,
        int seq,
        String mode,
        String gbisRouteId,
        String routeLabel,
        String boardStationId,
        String alightStationId,
        String boardName,
        String alightName,
        String matchStatus,
        OffsetDateTime plannedStartAt,
        OffsetDateTime plannedEndAt,
        Integer walkMin,
        OffsetDateTime userAlightAt
    ) {
    }
}
