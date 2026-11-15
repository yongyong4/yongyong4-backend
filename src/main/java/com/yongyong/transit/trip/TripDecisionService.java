
package com.yongyong.transit.trip;

import com.yongyong.transit.demo.DemoClock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TripDecisionService {

    @Autowired(required = false)
    private DemoClock demoClock;

    private LocalDateTime currentLocalNow() {
        return demoClock == null
            ? LocalDateTime.now(KST).withNano(0)
            : demoClock.now()
                .atZoneSameInstant(KST)
                .toLocalDateTime()
                .withNano(0);
    }

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final Set<String> STRATEGY_TYPES = Set.of(
        "KEEP", "BACKTRACK", "DETOUR",
        "ALT_ROUTE", "OTHER_MODE"
    );

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    public TripDecisionService(
        JdbcTemplate jdbc,
        JsonMapper jsonMapper
    ) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public Map<String, Object> decide(
        String deviceId,
        long tripId,
        long evaluationId,
        JsonNode request
    ) {
        if (deviceId == null || deviceId.isBlank()) {
            throw badRequest("기기 ID가 필요합니다.");
        }

        JsonNode acceptedNode = request == null
            ? null
            : request.get("accepted");

        if (acceptedNode == null || !acceptedNode.isBoolean()) {
            throw badRequest("accepted는 true 또는 false여야 합니다.");
        }

        boolean accepted = acceptedNode.asBoolean();

        List<Map<String, Object>> trips = jdbc.queryForList(
            """
            SELECT trip_id, current_route_id, current_status, ended_at
            FROM trip
            WHERE trip_id = ?
              AND device_id = ?
            FOR UPDATE
            """,
            tripId,
            deviceId
        );

        if (trips.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "이동 세션을 찾을 수 없습니다."
            );
        }

        Map<String, Object> trip = trips.get(0);

        if (trip.get("ended_at") != null) {
            throw conflict("이미 종료된 이동입니다.");
        }

        List<Map<String, Object>> evaluations = jdbc.queryForList(
            """
            SELECT evaluation_id, result, suggestion_json,
                   suggested_strategy, accepted, responded_at
            FROM evaluation
            WHERE evaluation_id = ?
              AND trip_id = ?
            FOR UPDATE
            """,
            evaluationId,
            tripId
        );

        if (evaluations.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "재평가 결과를 찾을 수 없습니다."
            );
        }

        Map<String, Object> evaluation = evaluations.get(0);

        if (!"SUGGEST".equals(evaluation.get("result"))) {
            throw conflict("경로 변경 제안이 없는 재평가입니다.");
        }

        String currentStatus = (String) trip.get("current_status");
        long currentRouteId = number(trip.get("current_route_id"));

        Object previousDecision = evaluation.get("accepted");

        if (previousDecision != null) {
            boolean previouslyAccepted = toBoolean(previousDecision);

            if (previouslyAccepted != accepted) {
                throw conflict("이미 다른 결정으로 처리된 제안입니다.");
            }

            return response(
                tripId,
                evaluationId,
                accepted,
                currentRouteId,
                currentStatus,
                evaluation.get("responded_at")
            );
        }

        // 현재 경로가 변경되었다면 이전 제안을 처리하지 않는다.
        // 이미 처리된 동일 요청은 위에서 멱등 처리한다.
        JsonNode suggestion = parseSuggestion(
            evaluation.get("suggestion_json")
        );
        JsonNode originRouteId = suggestion.get("originRouteId");

        if (originRouteId == null
            || !originRouteId.isIntegralNumber()
            || originRouteId.longValue() != currentRouteId) {
            throw conflict(
                "현재 경로가 변경되어 이전 제안을 처리할 수 없습니다."
            );
        }

        Timestamp respondedAt = Timestamp.valueOf(currentLocalNow());

        // 무시: 현재 경로 유지
        if (!accepted) {
            String nextStatus = currentStatus;

            if ("BOARD_FAILED".equals(currentStatus)) {
                nextStatus = "WAITING";
            }

            updateStatusIfChanged(
                tripId,
                currentStatus,
                nextStatus,
                respondedAt
            );

            jdbc.update(
                """
                UPDATE evaluation
                SET accepted = FALSE,
                    responded_at = ?
                WHERE evaluation_id = ?
                """,
                respondedAt,
                evaluationId
            );

            return response(
                tripId,
                evaluationId,
                false,
                currentRouteId,
                nextStatus,
                respondedAt
            );
        }

        JsonNode canAccept = suggestion.get("canAccept");

        if (canAccept != null
            && canAccept.isBoolean()
            && !canAccept.asBoolean()) {
            throw conflict("수락할 수 없는 경로 변경 제안입니다.");
        }

        JsonNode segments = suggestion.path("route").path("segments");

        if (!segments.isArray() || segments.isEmpty()) {
            throw conflict("제안된 경로의 구간 정보가 없습니다.");
        }

        String strategyType = (String) evaluation.get(
            "suggested_strategy"
        );

        if (strategyType == null || strategyType.isBlank()) {
            strategyType = requiredText(suggestion, "strategyType");
        }

        if (!STRATEGY_TYPES.contains(strategyType)
            || "KEEP".equals(strategyType)) {
            throw conflict("경로 변경 전략이 올바르지 않습니다.");
        }

        validateSegments(segments);

        Timestamp expectedArrivalAt = optionalTime(
            suggestion, "expectedArrivalAt"
        );

        String source = "BOARD_FAILED".equals(currentStatus)
            ? "AFTER_FAIL"
            : "SUGGESTED";

        String nextStatus = determineNextStatus(
            strategyType, segments
        );

        long newRouteId = insertRoute(
            tripId,
            evaluationId,
            source,
            strategyType,
            respondedAt
        );

        int seq = 1;

        for (JsonNode segment : segments) {
            jdbc.update(
                """
                INSERT INTO segment (
                    trip_route_id, seq, mode,
                    gbis_route_id, route_label,
                    board_station_id, alight_station_id,
                    board_name, alight_name, match_status,
                    planned_start_at, planned_end_at,
                    walk_min, user_alight_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                newRouteId,
                seq++,
                requiredText(segment, "mode"),
                optionalText(segment, "gbisRouteId"),
                optionalText(segment, "routeLabel"),
                optionalText(segment, "boardStationId"),
                optionalText(segment, "alightStationId"),
                optionalText(segment, "boardName"),
                optionalText(segment, "alightName"),
                requiredText(segment, "matchStatus"),
                optionalTime(segment, "plannedStartAt"),
                optionalTime(segment, "plannedEndAt"),
                optionalInt(segment, "walkMin"),
                optionalTime(segment, "userAlightAt")
            );
        }

        jdbc.update(
            """
            UPDATE trip
            SET current_route_id = ?,
                current_status = ?,
                expected_arrival_at =
                    COALESCE(?, expected_arrival_at)
            WHERE trip_id = ?
            """,
            newRouteId,
            nextStatus,
            expectedArrivalAt,
            tripId
        );

        insertStatusLogIfChanged(
            tripId,
            currentStatus,
            nextStatus,
            respondedAt
        );

        jdbc.update(
            """
            UPDATE evaluation
            SET accepted = TRUE,
                responded_at = ?
            WHERE evaluation_id = ?
            """,
            respondedAt,
            evaluationId
        );

        return response(
            tripId,
            evaluationId,
            true,
            newRouteId,
            nextStatus,
            respondedAt
        );
    }

    private String determineNextStatus(
        String strategyType,
        JsonNode segments
    ) {
        JsonNode firstSegment = segments.get(0);

        String firstMode = requiredText(firstSegment, "mode");
        Integer walkMin = optionalInt(firstSegment, "walkMin");

        if ("WALK".equals(firstMode)
            && walkMin != null
            && walkMin > 0) {
            return "MOVING";
        }

        if ("ALT_ROUTE".equals(strategyType)) {
            return "WAITING";
        }

        return "MOVING";
    }

    private long insertRoute(
        long tripId,
        long evaluationId,
        String source,
        String strategyType,
        Timestamp createdAt
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                INSERT INTO trip_route (
                    trip_id, source, evaluation_id,
                    strategy_type, created_at
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                Statement.RETURN_GENERATED_KEYS
            );

            ps.setLong(1, tripId);
            ps.setString(2, source);
            ps.setLong(3, evaluationId);
            ps.setString(4, strategyType);
            ps.setTimestamp(5, createdAt);

            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();

        if (key == null) {
            throw new IllegalStateException(
                "새 경로 ID를 생성하지 못했습니다."
            );
        }

        return key.longValue();
    }

    private void updateStatusIfChanged(
        long tripId,
        String before,
        String after,
        Timestamp changedAt
    ) {
        if (before.equals(after)) {
            return;
        }

        jdbc.update(
            "UPDATE trip SET current_status = ? WHERE trip_id = ?",
            after,
            tripId
        );

        insertStatusLogIfChanged(
            tripId, before, after, changedAt
        );
    }

    private void insertStatusLogIfChanged(
        long tripId,
        String before,
        String after,
        Timestamp changedAt
    ) {
        if (before.equals(after)) {
            return;
        }

        jdbc.update(
            """
            INSERT INTO trip_status_log (
                trip_id, segment_id, status,
                plate_no, changed_at
            )
            VALUES (?, NULL, ?, NULL, ?)
            """,
            tripId,
            after,
            changedAt
        );
    }

    private void validateSegments(JsonNode segments) {
        for (JsonNode segment : segments) {
            String mode = requiredText(segment, "mode");
            String matchStatus = requiredText(
                segment, "matchStatus"
            );

            if (!Set.of("WALK", "BUS", "SUBWAY").contains(mode)) {
                throw conflict(
                    "제안 경로의 이동수단이 올바르지 않습니다."
                );
            }

            if (!Set.of("MATCHED", "FAILED", "NA")
                .contains(matchStatus)) {
                throw conflict(
                    "제안 경로의 매칭 상태가 올바르지 않습니다."
                );
            }

            optionalTime(segment, "plannedStartAt");
            optionalTime(segment, "plannedEndAt");
            optionalTime(segment, "userAlightAt");

            Integer walkMin = optionalInt(segment, "walkMin");

            if (walkMin != null && walkMin < 0) {
                throw conflict(
                    "제안 경로의 도보 시간이 올바르지 않습니다."
                );
            }
        }
    }

    private JsonNode parseSuggestion(Object value) {
        if (value == null) {
            throw conflict(
                "저장된 경로 변경 제안이 없습니다."
            );
        }

        try {
            return jsonMapper.readTree(value.toString());
        } catch (Exception e) {
            throw conflict(
                "저장된 경로 변경 제안을 읽을 수 없습니다."
            );
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);

        if (value == null || value.isBlank()) {
            throw badRequest(field + " 값이 필요합니다.");
        }

        return value;
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);

        if (value == null || value.isNull()) {
            return null;
        }

        if (!value.isTextual()) {
            throw badRequest(
                field + " 형식이 올바르지 않습니다."
            );
        }

        return value.asText();
    }

    private Integer optionalInt(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);

        if (value == null || value.isNull()) {
            return null;
        }

        if (!value.isInt()) {
            throw badRequest(
                field + " 형식이 올바르지 않습니다."
            );
        }

        return value.intValue();
    }

    private Timestamp optionalTime(JsonNode node, String field) {
        String value = optionalText(node, field);

        if (value == null) {
            return null;
        }

        try {
            LocalDateTime time = OffsetDateTime.parse(value)
                .atZoneSameInstant(KST)
                .toLocalDateTime();

            return Timestamp.valueOf(time);
        } catch (Exception e) {
            throw badRequest(
                field + " 시각 형식이 올바르지 않습니다."
            );
        }
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }

        if (value instanceof Number number) {
            return number.intValue() != 0;
        }

        throw new IllegalStateException(
            "DB 수락 여부의 형식이 올바르지 않습니다."
        );
    }

    private long number(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalStateException(
                "현재 경로 ID가 올바르지 않습니다."
            );
        }

        return number.longValue();
    }

    private Map<String, Object> response(
        long tripId,
        long evaluationId,
        boolean accepted,
        long routeId,
        String status,
        Object respondedAt
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("tripId", tripId);
        result.put("evaluationId", evaluationId);
        result.put("accepted", accepted);
        result.put("currentRouteId", routeId);
        result.put("currentStatus", status);

        LocalDateTime dateTime = null;

        if (respondedAt instanceof Timestamp timestamp) {
            dateTime = timestamp.toLocalDateTime();
        } else if (respondedAt instanceof LocalDateTime time) {
            dateTime = time;
        }

        result.put(
            "respondedAt",
            dateTime == null
                ? null
                : dateTime.atZone(KST)
                  .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        );

        return result;
    }

    private ResponseStatusException badRequest(
        String message
    ) {
        return new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            message
        );
    }

    private ResponseStatusException conflict(
        String message
    ) {
        return new ResponseStatusException(
            HttpStatus.CONFLICT,
            message
        );
    }
}
