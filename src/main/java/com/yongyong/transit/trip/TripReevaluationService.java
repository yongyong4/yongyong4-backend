
package com.yongyong.transit.trip;

import com.yongyong.transit.analysis.service.CallBudget;
import com.yongyong.transit.analysis.service.RouteAnalysisService.BusOption;
import com.yongyong.transit.analysis.service.RouteAnalysisService.RouteInput;
import com.yongyong.transit.analysis.service.RouteAnalysisService.RouteSegment;
import com.yongyong.transit.strategy.service.StrategyService;
import com.yongyong.transit.strategy.service.StrategyService.Location;
import com.yongyong.transit.strategy.service.StrategyService.Mode;
import com.yongyong.transit.strategy.service.StrategyService.StrategyResponse;
import com.yongyong.transit.strategy.service.StrategyService.StrategyView;
import com.yongyong.transit.strategy.service.StrategyService.TripSegment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class TripReevaluationService {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final Set<String> TRIGGERS = Set.of(
        "AUTO", "REFRESH", "STATUS_CHANGE", "RESUME"
    );
    private static final int MAX_SAVED_ALTERNATIVES = 5;

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final StrategyService strategies;

    public TripReevaluationService(
        JdbcTemplate jdbc,
        JsonMapper jsonMapper,
        StrategyService strategies
    ) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.strategies = strategies;
    }

    @Transactional
    public Map<String, Object> reevaluate(
        String deviceId,
        long tripId,
        JsonNode request
    ) {
        long startedNs = System.nanoTime();
        CallBudget gbisBudget = CallBudget.of(1);
        checkDevice(deviceId);

        String trigger = requiredText(request, "triggerType");
        if (!TRIGGERS.contains(trigger)) {
            throw badRequest("올바르지 않은 재평가 트리거입니다.");
        }

        Integer requestedWalk = optionalNonnegativeInt(
            request, "remainingWalkMin"
        );
        String currentStationId = optionalText(
            request, "currentStationId"
        );
        Location location = location(
            request == null ? null : request.path("currentLocation")
        );
        OffsetDateTime now = OffsetDateTime.now(KST).withNano(0);

        Map<String, Object> trip = ownedTrip(deviceId, tripId);
        if (trip.get("ended_at") != null) {
            throw conflict("종료된 이동은 재평가할 수 없습니다.");
        }

        long routeId = asLong(trip.get("current_route_id"));
        String status = (String) trip.get("current_status");
        List<DbLeg> original = readLegs(routeId);

        if (original.isEmpty()) {
            throw conflict("현재 경로에 이동 구간이 없습니다.");
        }

        if (applyAlightUpdates(request, routeId, original, now)) {
            original = readLegs(routeId);
        }

        int boardedSeq = latestBoardedSeq(tripId, routeId);
        int nextBusIndex = -1;

        for (int i = 0; i < original.size(); i++) {
            DbLeg leg = original.get(i);
            if (leg.seq() > boardedSeq && "BUS".equals(leg.mode())) {
                nextBusIndex = i;
                break;
            }
        }

        DbLeg target = nextBusIndex < 0
            ? null : original.get(nextBusIndex);

        int from = 0;
        while (from < original.size()
            && original.get(from).seq() <= boardedSeq) {
            from++;
        }

        if (("WAITING".equals(status) || "BOARD_FAILED".equals(status))
            && nextBusIndex >= 0) {
            from = nextBusIndex;
        }

        List<DbLeg> remaining = original.subList(
            from, original.size()
        );

        OffsetDateTime baseTime = now;

        if ("BOARDED".equals(status) && boardedSeq > 0) {
            for (DbLeg leg : original) {
                if (leg.seq() == boardedSeq) {
                    OffsetDateTime alight = leg.userAlightAt() != null
                        ? leg.userAlightAt()
                        : leg.plannedEndAt();

                    if (alight != null && alight.isAfter(now)) {
                        baseTime = alight;
                    }
                    break;
                }
            }
        }

        int remainingWalk = 0;
        boolean walking = "MOVING".equals(status);

        // 남은 도보: 보낸 값 → 현재 위치로 계산 → 경과 시간으로 추정
        if (walking) {
            Integer reliableWalk = requestedWalk != null
                ? requestedWalk
                : walkFromLocation(location, remaining, target, now);

            if (reliableWalk != null) {
                remainingWalk = reliableWalk;
            } else {
                int originalWalk = 0;

                for (DbLeg leg : remaining) {
                    if (!"WALK".equals(leg.mode())) {
                        break;
                    }
                    originalWalk += leg.walkMin() != null
                        ? leg.walkMin()
                        : durationMin(
                        leg.plannedStartAt(), leg.plannedEndAt()
                    );
                }

                OffsetDateTime movingSince = latestMovingAt(tripId);
                if (movingSince == null) {
                    movingSince = asTime(trip.get("started_at"));
                }

                long elapsed = movingSince == null ? 0
                    : Math.max(
                    0, Duration.between(movingSince, now).toMinutes()
                );

                remainingWalk = (int) Math.max(0, originalWalk - elapsed);
            }

            // 보낸 값이나 위치로 0분이면 정류장에 도착한 것으로 보고 도보 구간을 뺀다
            if (reliableWalk != null && reliableWalk == 0) {
                int firstNonWalk = 0;
                while (firstNonWalk < remaining.size()
                    && "WALK".equals(remaining.get(firstNonWalk).mode())) {
                    firstNonWalk++;
                }
                if (firstNonWalk < remaining.size()) {
                    remaining = remaining.subList(
                        firstNonWalk, remaining.size()
                    );
                    walking = false;
                }
            }
        }

        Map<Integer, OffsetDateTime> alightAtBySegment =
            new HashMap<>();

        for (int i = 0; i < remaining.size(); i++) {
            DbLeg leg = remaining.get(i);

            if ("SUBWAY".equals(leg.mode())
                && leg.userAlightAt() != null) {
                alightAtBySegment.put(
                    i,
                    leg.userAlightAt()
                );
            }
        }

        RouteInput currentRoute = buildRoute(
            remaining,
            remainingWalk,
            walking
        );

        List<RouteInput> alternatives = savedAlternatives(
            trip.get("alt_routes_json"),
            target,
            currentStationId
        );

        StrategyResponse evaluated = null;
        List<StrategyView> possible = new ArrayList<>();
        StrategyView current = null;
        String error = null;

        // 기능 3의 공통 전략 비교 로직 사용
        // REEVALUATION 모드에서는 CallBudget.none() 적용
        if (!remaining.isEmpty()) {
            try {
                evaluated = strategies.evaluate(
                    currentRoute,
                    alternatives,
                    alightAtBySegment,
                    location,
                    baseTime,
                    Mode.REEVALUATION,
                    now,
                    gbisBudget
                );

                for (StrategyView view : evaluated.strategies()) {
                    if (view.isCurrent()) {
                        current = view;
                    } else {
                        possible.add(view);
                    }
                }

            } catch (ResponseStatusException e) {
                error = e.getReason();
            }
        }

        // 같은 정류장의 저장된 대안은 기능 3에서
        // 우회 후보로 제외될 수 있어 별도로 평가
        if (target != null) {
            Set<String> known = new HashSet<>();

            if (current != null) {
                known.add(signature(current.route().segments()));
            }
            for (StrategyView view : possible) {
                known.add(signature(view.route().segments()));
            }

            for (RouteInput candidate : alternatives) {
                if (candidate.segments().stream().noneMatch(
                    s -> "BUS".equals(s.mode())
                )) {
                    continue;
                }

                if (known.contains(signature(candidate))) {
                    continue;
                }

                try {
                    StrategyResponse separate = strategies.evaluate(
                        candidate,
                        List.of(),
                        Map.of(),
                        null,
                        baseTime,
                        Mode.REEVALUATION,
                    now,
                    gbisBudget
                );

                    separate.strategies().stream()
                        .filter(StrategyView::isCurrent)
                        .findFirst()
                        .ifPresent(view -> {
                            if (hasEvidence(view)) {
                                possible.add(view);
                            }
                        });

                } catch (ResponseStatusException ignored) {
                    // 분석 불가 후보는 추천하지 않는다.
                }
            }
        }

        Integer currentValue = comparableValue(current);

        // 버스 탑승 소요 시간이 없는 경로는
        // 잘못된 비교값으로 추천하지 않는다.
        boolean incomplete = currentRoute.segments().stream()
            .anyMatch(s ->
                "BUS".equals(s.mode())
                    && (s.durationMin() == null
                    || s.durationMin() <= 0)
            );

        if (incomplete) {
            currentValue = null;
        }

        boolean boardingFailed = "BOARD_FAILED".equals(status);

        Integer waitMin = boardingFailed && current != null
            && current.busArrivalAt() != null
            ? current.totalMinIfBoard()
            : null;

        if (boardingFailed) {
            currentValue = waitMin;
        }

        String currentSignature = current == null
            ? null : signature(current.route().segments());

        List<StrategyView> viable = possible.stream()
            .filter(v ->
                hasEvidence(v) && comparableValue(v) != null
            )
            .filter(v ->
                !Objects.equals(
                    currentSignature,
                    signature(v.route().segments())
                )
            )
            .filter(v ->
                !wouldReverseWithinFourMinutes(
                    tripId, routeId, v, now
                )
            )
            .sorted(Comparator
                .comparingInt(
                    (StrategyView v) -> comparableValue(v)
                )
                .thenComparingInt(v ->
                    v.totalMinIfBoard() == null
                        ? Integer.MAX_VALUE
                        : v.totalMinIfBoard()
                )
                .thenComparingInt(StrategyView::extraWalkMin)
            )
            .toList();

        StrategyView best = viable.isEmpty()
            ? null : viable.get(0);

        Integer bestValue = best == null
            ? null : comparableValue(best);

        // 평상시에는 엄격히 3분 초과
        // 탑승 실패 시에는 3분 기준을 적용하지 않음
        boolean propose = currentValue != null
            && bestValue != null
            && (
            boardingFailed
                || currentValue - bestValue > 3
        );

        String result = propose ? "SUGGEST" : "KEEP";

        Integer savedMin = propose
            ? currentValue - bestValue
            : null;

        ObjectNode suggestion = propose
            ? createSuggestion(best, routeId, savedMin)
            : null;

        OffsetDateTime dataBasisAt = evaluated == null
            ? null : evaluated.dataBasisAt();

        boolean delayed = evaluated == null
            || evaluated.dataDelayed()
            || dataBasisAt == null
            || Duration.between(dataBasisAt, now).getSeconds() >= 240;

        long latencyMs = Duration.ofNanos(
            System.nanoTime() - startedNs
        ).toMillis();

        long evaluationId = saveEvaluation(
            tripId,
            trigger,
            now,
            dataBasisAt,
            result,
            suggestion,
            target,
            current,
            currentValue,
            propose ? bestValue : null,
            latencyMs
        );

        if (current != null
            && current.expectedArrivalAt() != null) {
            jdbc.update(
                """
                UPDATE trip
                SET expected_arrival_at = ?
                WHERE trip_id = ?
                """,
                timestamp(current.expectedArrivalAt()),
                tripId
            );
        }

        if (propose) {
            jdbc.update(
                """
                UPDATE trip
                SET last_suggested_at = ?
                WHERE trip_id = ?
                """,
                timestamp(now),
                tripId
            );
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tripId", tripId);
        response.put("evaluationId", evaluationId);
        response.put("triggerType", trigger);
        response.put("currentStatus", status);
        response.put("evaluatedAt", now);
        response.put("dataBasisAt", dataBasisAt);
        response.put("dataDelayed", delayed);
        response.put("result", result);
        response.put(
            "targetSegmentId",
            target == null ? null : target.segmentId()
        );
        response.put("currentValueMin", currentValue);
        response.put(
            "suggestedValueMin",
            propose ? bestValue : null
        );
        response.put("savedMinutes", savedMin);
        response.put(
            "nextSegment",
            current == null || current.analysis().isEmpty()
                ? null
                : current.analysis().get(0)
        );
        response.put("suggestion", suggestion);
        response.put(
            "waitOption",
            boardingFailed ? waitOption(current) : null
        );
        response.put(
            "reason",
            propose
                ? boardingFailed
                  ? "탑승 실패 후 다른 경로를 선택할 수 있어요."
                  : savedMin + "분 더 빨리 도착할 수 있어요."
                : error != null
                  ? error
                  : currentValue == null
                    ? "현재 데이터로는 경로를 비교할 수 없어요."
                    : "지금 경로 그대로 가세요."
        );

        return response;
    }

    @Transactional
    public Map<String, Object> waitForNextBus(
        String deviceId,
        long tripId
    ) {
        checkDevice(deviceId);

        Map<String, Object> trip = ownedTrip(deviceId, tripId);

        if (trip.get("ended_at") != null
            || !"BOARD_FAILED".equals(trip.get("current_status"))) {
            throw conflict(
                "탑승 실패 상태에서만 다음 버스 대기를 선택할 수 있습니다."
            );
        }

        List<Map<String, Object>> failures = jdbc.queryForList(
            """
            SELECT segment_id
            FROM trip_status_log
            WHERE trip_id = ?
              AND status = 'BOARD_FAILED'
            ORDER BY changed_at DESC, log_id DESC
            LIMIT 1
            """,
            tripId
        );

        Object segmentId = failures.isEmpty()
            ? null : failures.get(0).get("segment_id");

        Timestamp now = timestamp(OffsetDateTime.now(KST));

        jdbc.update(
            """
            UPDATE trip
            SET current_status = 'WAITING'
            WHERE trip_id = ?
            """,
            tripId
        );

        jdbc.update(
            """
            INSERT INTO trip_status_log (
                trip_id, segment_id, status, plate_no, changed_at
            )
            VALUES (?, ?, 'WAITING', NULL, ?)
            """,
            tripId,
            segmentId,
            now
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tripId", tripId);
        response.put("currentStatus", "WAITING");
        response.put("segmentId", segmentId);
        response.put("reevaluationRequired", true);
        return response;
    }

    private List<RouteInput> savedAlternatives(
        Object raw,
        DbLeg target,
        String stationId
    ) {
        if (raw == null || target == null) {
            return List.of();
        }

        JsonNode data;
        try {
            data = jsonMapper.readTree(raw.toString());
        } catch (Exception e) {
            return List.of();
        }

        if (!data.isArray()) {
            return List.of();
        }

        List<RouteInput> result = new ArrayList<>();

        for (JsonNode item : data) {
            if (result.size() >= MAX_SAVED_ALTERNATIVES) {
                break;
            }

            JsonNode route = item.path("route");
            JsonNode segments = route.path("segments").isArray()
                ? route.path("segments")
                : item.path("segments");

            if (!segments.isArray() || segments.isEmpty()) {
                continue;
            }

            List<RouteSegment> parsed = new ArrayList<>();
            boolean valid = true;

            for (JsonNode segment : segments) {
                RouteSegment converted = parseSegment(
                    segment, parsed.size()
                );
                if (converted == null) {
                    valid = false;
                    break;
                }
                parsed.add(converted);
            }

            if (!valid || parsed.isEmpty()) {
                continue;
            }

            String firstBoard = parsed.stream()
                .filter(s -> "BUS".equals(s.mode()))
                .map(RouteSegment::startLocalStationId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

            if (!Objects.equals(target.boardStationId(), firstBoard)
                && !Objects.equals(stationId, firstBoard)) {
                // 현재 위치가 확인되지 않은 과거 출발지 후보 제외
                continue;
            }

            int firstBus = 0;
            while (firstBus < parsed.size()
                && !"BUS".equals(parsed.get(firstBus).mode())) {
                firstBus++;
            }

            if (firstBus >= parsed.size()) {
                continue;
            }

            result.add(
                new RouteInput(
                    result.size(),
                    null,
                    renumber(
                        parsed.subList(firstBus, parsed.size())
                    )
                )
            );
        }

        return result;
    }

    private List<RouteSegment> renumber(
        List<RouteSegment> segments
    ) {
        List<RouteSegment> result = new ArrayList<>();

        for (RouteSegment segment : segments) {
            result.add(
                new RouteSegment(
                    result.size(),
                    segment.mode(),
                    segment.durationMin(),
                    segment.startName(),
                    segment.endName(),
                    segment.startLocalStationId(),
                    segment.endLocalStationId(),
                    segment.matchStatus(),
                    segment.busOptions()
                )
            );
        }

        return result;
    }

    private RouteSegment parseSegment(
        JsonNode node,
        int index
    ) {
        if (node == null || !node.isObject()) {
            return null;
        }

        String mode = optionalText(node, "mode");

        if (mode == null
            || !Set.of("WALK", "BUS", "SUBWAY").contains(mode)) {
            return null;
        }

        Integer duration = optionalNonnegativeInt(
            node, "durationMin"
        );

        if (duration == null && "WALK".equals(mode)) {
            duration = optionalNonnegativeInt(node, "walkMin");
        }

        if (duration == null) {
            duration = durationMin(
                parseTime(optionalText(node, "plannedStartAt")),
                parseTime(optionalText(node, "plannedEndAt"))
            );
        }

        String routeId = optionalText(node, "gbisRouteId");
        String routeLabel = optionalText(node, "routeLabel");
        List<BusOption> options = new ArrayList<>();

        JsonNode existingOptions = node.path("busOptions");

        if (existingOptions.isArray()) {
            for (JsonNode option : existingOptions) {
                String id = optionalText(option, "localRouteId");
                if (id != null) {
                    options.add(
                        new BusOption(
                            optionalText(option, "busNo"),
                            id
                        )
                    );
                }
            }
        } else if (routeId != null) {
            options.add(new BusOption(routeLabel, routeId));
        }

        String board = optionalText(node, "boardStationId");
        if (board == null) {
            board = optionalText(node, "startLocalStationId");
        }

        String alight = optionalText(node, "alightStationId");
        if (alight == null) {
            alight = optionalText(node, "endLocalStationId");
        }

        String matchStatus = optionalText(node, "matchStatus");
        if (matchStatus == null) {
            matchStatus = "BUS".equals(mode)
                ? "MATCHED" : "NA";
        }

        String start = optionalText(node, "boardName");
        if (start == null) {
            start = optionalText(node, "startName");
        }

        String end = optionalText(node, "alightName");
        if (end == null) {
            end = optionalText(node, "endName");
        }

        return new RouteSegment(
            index,
            mode,
            duration,
            start,
            end,
            board,
            alight,
            matchStatus,
            options
        );
    }

    /**
     * 현재 위치에서 다음 버스 승차 정류장까지 걷는 시간(분).
     * 다음 버스 전 남은 구간이 모두 도보일 때만 계산하고, 못 구하면 null.
     */
    private Integer walkFromLocation(
        Location location,
        List<DbLeg> remaining,
        DbLeg target,
        OffsetDateTime now
    ) {
        if (location == null
            || target == null
            || target.gbisRouteId() == null
            || target.boardStationId() == null) {
            return null;
        }

        for (DbLeg leg : remaining) {
            if (leg == target) {
                return strategies.walkMinToStation(
                    location,
                    target.gbisRouteId(),
                    target.boardStationId(),
                    now
                );
            }
            if (!"WALK".equals(leg.mode())) {
                return null;
            }
        }

        return null;
    }

    private RouteInput buildRoute(
        List<DbLeg> legs,
        int remainingWalk,
        boolean moving
    ) {
        List<RouteSegment> segments = new ArrayList<>();
        boolean initialWalk = moving;

        for (DbLeg leg : legs) {
            int duration = "WALK".equals(leg.mode())
                && leg.walkMin() != null
                ? leg.walkMin()
                : durationMin(
                leg.plannedStartAt(),
                leg.plannedEndAt()
            );

            if ("WALK".equals(leg.mode()) && initialWalk) {
                duration = remainingWalk;
                initialWalk = false;
            } else if (!"WALK".equals(leg.mode())) {
                initialWalk = false;
            }

            List<BusOption> options = leg.gbisRouteId() == null
                ? List.of()
                : List.of(
                new BusOption(
                    leg.routeLabel(),
                    leg.gbisRouteId()
                )
            );

            segments.add(
                new RouteSegment(
                    segments.size(),
                    leg.mode(),
                    duration,
                    leg.boardName(),
                    leg.alightName(),
                    leg.boardStationId(),
                    leg.alightStationId(),
                    leg.matchStatus(),
                    options
                )
            );
        }

        return new RouteInput(0, null, segments);
    }

    private boolean hasEvidence(StrategyView view) {
        if (view == null
            || view.route() == null
            || view.route().segments() == null
            || view.route().segments().isEmpty()) {
            return false;
        }

        boolean hasBus = view.route().segments().stream()
            .anyMatch(s -> "BUS".equals(s.mode()));

        return !hasBus || view.busArrivalAt() != null;
    }

    private Integer comparableValue(StrategyView view) {
        return hasEvidence(view)
            ? view.comparisonValueMin()
            : null;
    }

    private Map<String, Object> waitOption(
        StrategyView current
    ) {
        Map<String, Object> wait = new LinkedHashMap<>();
        wait.put("action", "WAIT_FOR_NEXT_BUS");
        wait.put("endpoint", "boarding-failure/wait");
        wait.put(
            "nextBusArrivalAt",
            current == null ? null : current.busArrivalAt()
        );
        wait.put(
            "boardingStage",
            current == null ? null : current.strategyRiskStage()
        );
        wait.put(
            "totalMin",
            current == null ? null : current.totalMinIfBoard()
        );

        return wait;
    }

    private ObjectNode createSuggestion(
        StrategyView view,
        long originRouteId,
        int savedMin
    ) {
        ObjectNode root = jsonMapper.createObjectNode();

        String strategy = view.strategyType().name();
        if ("KEEP".equals(strategy)) {
            strategy = "ALT_ROUTE";
        }

        root.put("strategyType", strategy);
        root.put("originRouteId", originRouteId);
        root.put("canAccept", true);
        root.put("savedMinutes", savedMin);
        root.put("title", view.title());
        root.put(
            "reason",
            savedMin > 0
                ? savedMin + "분 더 빨리 도착할 수 있어요."
                : "탑승 실패 후 선택 가능한 대안입니다."
        );

        if (view.expectedArrivalAt() != null) {
            root.put(
                "expectedArrivalAt",
                view.expectedArrivalAt().toString()
            );
        }

        ObjectNode route = root.putObject("route");
        ArrayNode segments = route.putArray("segments");

        for (TripSegment segment : view.route().segments()) {
            ObjectNode node = segments.addObject();

            node.put("mode", segment.mode());
            node.put("matchStatus", segment.matchStatus());

            put(node, "gbisRouteId", segment.gbisRouteId());
            put(node, "routeLabel", segment.routeLabel());
            put(node, "boardStationId", segment.boardStationId());
            put(node, "alightStationId", segment.alightStationId());
            put(node, "boardName", segment.boardName());
            put(node, "alightName", segment.alightName());

            if (segment.walkMin() != null) {
                node.put("walkMin", segment.walkMin());
            }
            if (segment.plannedStartAt() != null) {
                node.put(
                    "plannedStartAt",
                    segment.plannedStartAt().toString()
                );
            }
            if (segment.plannedEndAt() != null) {
                node.put(
                    "plannedEndAt",
                    segment.plannedEndAt().toString()
                );
            }
            if (segment.userAlightAt() != null) {
                node.put(
                    "userAlightAt",
                    segment.userAlightAt().toString()
                );
            }
        }

        return root;
    }

    private boolean wouldReverseWithinFourMinutes(
        long tripId,
        long currentRouteId,
        StrategyView candidate,
        OffsetDateTime now
    ) {
        List<Map<String, Object>> current = jdbc.queryForList(
            """
            SELECT source, created_at
            FROM trip_route
            WHERE trip_id = ?
              AND trip_route_id = ?
            """,
            tripId,
            currentRouteId
        );

        if (current.isEmpty()
            || "INITIAL".equals(current.get(0).get("source"))) {
            return false;
        }

        OffsetDateTime lastChanged = asTime(
            current.get(0).get("created_at")
        );

        if (lastChanged == null
            || Duration.between(lastChanged, now).getSeconds() >= 240) {
            return false;
        }

        List<Long> previous = jdbc.queryForList(
            """
            SELECT trip_route_id
            FROM trip_route
            WHERE trip_id = ?
              AND trip_route_id <> ?
            ORDER BY created_at DESC, trip_route_id DESC
            LIMIT 1
            """,
            Long.class,
            tripId,
            currentRouteId
        );

        if (previous.isEmpty()) {
            return false;
        }

        String oldSignature = signatureDb(
            readLegs(previous.get(0))
        );

        return !oldSignature.isBlank()
            && oldSignature.equals(
            signature(candidate.route().segments())
        );
    }

    private String signature(RouteInput route) {
        StringBuilder result = new StringBuilder();

        for (RouteSegment segment : route.segments()) {
            if ("BUS".equals(segment.mode())) {
                result.append(
                    segment.busOptions() != null
                        && !segment.busOptions().isEmpty()
                        ? segment.busOptions().get(0).localRouteId()
                        : "?"
                );
                result.append('|')
                    .append(segment.startLocalStationId())
                    .append('|')
                    .append(segment.endLocalStationId())
                    .append(';');
            }
        }

        return result.toString();
    }

    private String signature(List<TripSegment> segments) {
        StringBuilder result = new StringBuilder();

        for (TripSegment segment : segments) {
            if ("BUS".equals(segment.mode())) {
                result.append(segment.gbisRouteId())
                    .append('|')
                    .append(segment.boardStationId())
                    .append('|')
                    .append(segment.alightStationId())
                    .append(';');
            }
        }

        return result.toString();
    }

    private String signatureDb(List<DbLeg> segments) {
        StringBuilder result = new StringBuilder();

        for (DbLeg segment : segments) {
            if ("BUS".equals(segment.mode())) {
                result.append(segment.gbisRouteId())
                    .append('|')
                    .append(segment.boardStationId())
                    .append('|')
                    .append(segment.alightStationId())
                    .append(';');
            }
        }

        return result.toString();
    }

    private long saveEvaluation(
        long tripId,
        String trigger,
        OffsetDateTime now,
        OffsetDateTime basis,
        String result,
        ObjectNode suggestion,
        DbLeg target,
        StrategyView current,
        Integer currentValue,
        Integer bestValue,
        long latencyMs
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                INSERT INTO evaluation (
                    trip_id, trigger_type,
                    evaluated_at, data_basis_at,
                    result, suggestion_json,
                    target_segment_id,
                    pred_plate_no, pred_arrival_at, pred_seat,
                    slack_stage, seat_stage, model_version,
                    suggested_strategy,
                    current_value_min, suggested_value_min,
                    latency_ms
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                Statement.RETURN_GENERATED_KEYS
            );

            ps.setLong(1, tripId);
            ps.setString(2, trigger);
            ps.setTimestamp(3, timestamp(now));
            ps.setTimestamp(4, timestamp(basis));
            ps.setString(5, result);
            ps.setString(
                6,
                suggestion == null ? null : suggestion.toString()
            );

            if (target == null) {
                ps.setNull(7, Types.BIGINT);
            } else {
                ps.setLong(7, target.segmentId());
            }

            // 기능 3 공개 DTO에 없는 메타데이터는 추측하지 않음
            ps.setString(8, null);
            ps.setTimestamp(
                9,
                current == null
                    ? null
                    : timestamp(current.busArrivalAt())
            );

            if (current == null || current.remainSeats() == null) {
                ps.setNull(10, Types.INTEGER);
            } else {
                ps.setInt(10, current.remainSeats());
            }

            var first = current == null || current.analysis().isEmpty()
                ? null
                : current.analysis().get(0);

            ps.setString(
                11,
                first == null || first.slackStage() == null
                    ? null : first.slackStage().name()
            );
            ps.setString(
                12,
                first == null || first.seatStage() == null
                    ? null : first.seatStage().name()
            );
            ps.setString(13, null);

            ps.setString(
                14,
                suggestion == null
                    ? null
                    : suggestion.path("strategyType").asText()
            );

            if (currentValue == null) {
                ps.setNull(15, Types.INTEGER);
            } else {
                ps.setInt(15, currentValue);
            }

            if (bestValue == null) {
                ps.setNull(16, Types.INTEGER);
            } else {
                ps.setInt(16, bestValue);
            }

            ps.setInt(
                17,
                (int) Math.min(Integer.MAX_VALUE, latencyMs)
            );

            return ps;
        }, keyHolder);

        if (keyHolder.getKey() == null) {
            throw new IllegalStateException(
                "재평가 ID를 생성하지 못했습니다."
            );
        }

        return keyHolder.getKey().longValue();
    }

    private List<DbLeg> readLegs(long routeId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            """
            SELECT
                segment_id, seq, mode,
                gbis_route_id, route_label,
                board_station_id, alight_station_id,
                board_name, alight_name,
                match_status,
                planned_start_at, planned_end_at,
                walk_min, user_alight_at
            FROM segment
            WHERE trip_route_id = ?
            ORDER BY seq
            """,
            routeId
        );

        List<DbLeg> result = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            result.add(
                new DbLeg(
                    asLong(row.get("segment_id")),
                    ((Number) row.get("seq")).intValue(),
                    (String) row.get("mode"),
                    (String) row.get("gbis_route_id"),
                    (String) row.get("route_label"),
                    (String) row.get("board_station_id"),
                    (String) row.get("alight_station_id"),
                    (String) row.get("board_name"),
                    (String) row.get("alight_name"),
                    (String) row.get("match_status"),
                    asTime(row.get("planned_start_at")),
                    asTime(row.get("planned_end_at")),
                    row.get("walk_min") == null
                        ? null
                        : ((Number) row.get("walk_min")).intValue(),
                    asTime(row.get("user_alight_at"))
                )
            );
        }

        return result;
    }

    private int latestBoardedSeq(
        long tripId,
        long routeId
    ) {
        Integer seq = jdbc.queryForObject(
            """
            SELECT MAX(s.seq)
            FROM trip_status_log l
            JOIN segment s ON s.segment_id = l.segment_id
            WHERE l.trip_id = ?
              AND s.trip_route_id = ?
              AND l.status = 'BOARDED'
            """,
            Integer.class,
            tripId,
            routeId
        );

        return seq == null ? 0 : seq;
    }

    private OffsetDateTime latestMovingAt(long tripId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            """
            SELECT changed_at
            FROM trip_status_log
            WHERE trip_id = ?
              AND status = 'MOVING'
            ORDER BY changed_at DESC, log_id DESC
            LIMIT 1
            """,
            tripId
        );

        return rows.isEmpty()
            ? null
            : asTime(rows.get(0).get("changed_at"));
    }

    private boolean applyAlightUpdates(
        JsonNode request,
        long routeId,
        List<DbLeg> currentLegs,
        OffsetDateTime now
    ) {
        JsonNode updates = request == null
            ? null : request.get("alightUpdates");

        if (updates == null || updates.isNull()) {
            return false;
        }

        if (!updates.isArray()
            || updates.isEmpty()
            || updates.size() > 10) {
            throw badRequest(
                "alightUpdates는 1~10개의 수정 항목이어야 합니다."
            );
        }

        Set<Long> seen = new HashSet<>();
        List<AlightChange> changes = new ArrayList<>();

        for (JsonNode update : updates) {
            if (update == null || !update.isObject()) {
                throw badRequest(
                    "하차 시각 수정 항목이 올바르지 않습니다."
                );
            }

            JsonNode segmentNode = update.get("segmentId");

            if (segmentNode == null
                || !segmentNode.isIntegralNumber()
                || segmentNode.longValue() <= 0) {
                throw badRequest(
                    "올바른 segmentId가 필요합니다."
                );
            }

            long segmentId = segmentNode.longValue();

            if (!seen.add(segmentId)) {
                throw badRequest(
                    "동일한 구간을 중복 수정할 수 없습니다."
                );
            }

            DbLeg target = currentLegs.stream()
                .filter(leg -> leg.segmentId() == segmentId)
                .findFirst()
                .orElse(null);

            if (target == null
                || !"SUBWAY".equals(target.mode())) {
                throw badRequest(
                    "현재 경로의 지하철 구간만 수정할 수 있습니다."
                );
            }

            String value = requiredText(
                update, "userAlightAt"
            );

            OffsetDateTime alightAt;

            try {
                alightAt = OffsetDateTime.parse(value)
                    .withOffsetSameInstant(KST);
            } catch (RuntimeException e) {
                throw badRequest(
                    "userAlightAt은 시간대가 포함된 ISO 시각이어야 합니다."
                );
            }

            if (!alightAt.isAfter(now)) {
                throw badRequest(
                    "하차 예정 시각은 현재 시각 이후여야 합니다."
                );
            }

            changes.add(
                new AlightChange(segmentId, alightAt)
            );
        }

        for (AlightChange change : changes) {
            jdbc.update(
                """
                UPDATE segment
                SET user_alight_at = ?
                WHERE segment_id = ?
                  AND trip_route_id = ?
                  AND mode = 'SUBWAY'
                """,
                timestamp(change.alightAt()),
                change.segmentId(),
                routeId
            );
        }

        return true;
    }

    private record AlightChange(
        long segmentId,
        OffsetDateTime alightAt
    ) {}

    private Map<String, Object> ownedTrip(
        String deviceId,
        long tripId
    ) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            """
            SELECT
                trip_id, current_route_id,
                current_status, ended_at,
                started_at, alt_routes_json
            FROM trip
            WHERE trip_id = ?
              AND device_id = ?
            FOR UPDATE
            """,
            tripId,
            deviceId
        );

        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "이동 세션을 찾을 수 없습니다."
            );
        }

        return rows.get(0);
    }

    private Location location(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }

        JsonNode x = node.get("x");
        JsonNode y = node.get("y");

        if (x == null || y == null
            || !x.isNumber() || !y.isNumber()) {
            return null;
        }

        if (x.asDouble() < -180 || x.asDouble() > 180
            || y.asDouble() < -90 || y.asDouble() > 90) {
            throw badRequest("현재 위치 좌표가 올바르지 않습니다.");
        }

        return new Location(x.asDouble(), y.asDouble());
    }

    private static int durationMin(
        OffsetDateTime from,
        OffsetDateTime to
    ) {
        return from == null || to == null
            ? 0
            : (int) Math.max(
            0, Duration.between(from, to).toMinutes()
        );
    }

    private static OffsetDateTime parseTime(String raw) {
        if (raw == null) {
            return null;
        }

        try {
            return OffsetDateTime.parse(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static OffsetDateTime asTime(Object raw) {
        if (raw instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().atOffset(KST);
        }
        if (raw instanceof LocalDateTime dateTime) {
            return dateTime.atOffset(KST);
        }
        if (raw instanceof OffsetDateTime dateTime) {
            return dateTime.withOffsetSameInstant(KST);
        }
        return null;
    }

    private static Timestamp timestamp(OffsetDateTime time) {
        return time == null
            ? null
            : Timestamp.valueOf(
            time.withOffsetSameInstant(KST).toLocalDateTime()
        );
    }

    private static long asLong(Object raw) {
        return ((Number) raw).longValue();
    }

    private static String optionalText(
        JsonNode node,
        String name
    ) {
        if (node == null
            || node.get(name) == null
            || node.get(name).isNull()) {
            return null;
        }

        if (!node.get(name).isTextual()) {
            throw badRequest(name + "은 문자열이어야 합니다.");
        }

        return node.get(name).asText();
    }

    private static String requiredText(
        JsonNode node,
        String name
    ) {
        String value = optionalText(node, name);

        if (value == null || value.isBlank()) {
            throw badRequest(name + " 값이 필요합니다.");
        }

        return value;
    }

    private static Integer optionalNonnegativeInt(
        JsonNode node,
        String name
    ) {
        if (node == null
            || node.get(name) == null
            || node.get(name).isNull()) {
            return null;
        }

        if (!node.get(name).isInt() || node.get(name).asInt() < 0) {
            throw badRequest(name + "은 0 이상의 정수여야 합니다.");
        }

        return node.get(name).asInt();
    }

    private static void put(
        ObjectNode object,
        String name,
        String value
    ) {
        if (value != null) {
            object.put(name, value);
        }
    }

    private static void checkDevice(String deviceId) {
        try {
            UUID.fromString(deviceId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw badRequest("X-Device-Id가 올바르지 않습니다.");
        }
    }

    private static ResponseStatusException badRequest(
        String message
    ) {
        return new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            message
        );
    }

    private static ResponseStatusException conflict(
        String message
    ) {
        return new ResponseStatusException(
            HttpStatus.CONFLICT,
            message
        );
    }

    private record DbLeg(
        long segmentId,
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
    ) {}
}
