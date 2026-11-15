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

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class TripService {

    @Autowired(required = false)
    private DemoClock demoClock;

    private LocalDateTime currentLocalNow() {
        return demoClock == null
            ? LocalDateTime.now(KST)
            : demoClock.now().atZoneSameInstant(KST).toLocalDateTime();
    }


    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;

    public TripService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 이동 세션 생성 및 경로 저장
    @Transactional
    public Map<String, Object> createTrip(
        String deviceId,
        JsonNode request
    ) {
        validateDeviceId(deviceId);

        String destName = requiredText(request, "destName");
        String strategyType = requiredText(request, "strategyType");

        if (!Set.of(
            "KEEP", "BACKTRACK", "DETOUR",
            "ALT_ROUTE", "OTHER_MODE"
        ).contains(strategyType)) {
            throw badRequest("올바르지 않은 전략 종류입니다.");
        }

        JsonNode selectedRoute = request.path("selectedRoute");
        JsonNode segments = selectedRoute.path("segments");
        JsonNode alternativeRoutes = request.path("alternativeRoutes");

        if (!selectedRoute.isObject()
            || !segments.isArray()
            || segments.isEmpty()) {
            throw badRequest("선택한 경로 정보가 올바르지 않습니다.");
        }

        if (!alternativeRoutes.isArray()) {
            throw badRequest("다른 후보 경로 정보가 올바르지 않습니다.");
        }

        // 저장 전에 모든 구간 검증
        int expectedSeq = 1;

        for (JsonNode segment : segments) {
            int seq = requiredInt(segment, "seq");
            String mode = requiredText(segment, "mode");
            String matchStatus = requiredText(segment, "matchStatus");

            if (seq != expectedSeq++) {
                throw badRequest("이동 구간 순서가 올바르지 않습니다.");
            }

            if (!Set.of("WALK", "BUS", "SUBWAY").contains(mode)) {
                throw badRequest("지원하지 않는 이동수단입니다.");
            }

            if (!Set.of(
                "MATCHED", "FAILED", "NA"
            ).contains(matchStatus)) {
                throw badRequest("구간 매칭 상태가 올바르지 않습니다.");
            }

            optionalTime(segment, "plannedStartAt");
            optionalTime(segment, "plannedEndAt");
            optionalTime(segment, "userAlightAt");

            Integer walkMin = optionalInt(segment, "walkMin");

            if (walkMin != null && walkMin < 0) {
                throw badRequest("도보시간은 음수일 수 없습니다.");
            }
        }

        Timestamp expectedArrivalAt =
            optionalTime(request, "expectedArrivalAt");

        LocalDateTime now = currentLocalNow();
        Timestamp nowTimestamp = Timestamp.valueOf(now);

        // 익명 기기 등록
        jdbc.update(
            "INSERT IGNORE INTO device (device_id, created_at) VALUES (?, ?)",
            deviceId,
            nowTimestamp
        );

        // 동일 기기에서 이동 세션이 중복 생성되지 않도록 잠금
        jdbc.queryForList(
            "SELECT device_id FROM device WHERE device_id = ? FOR UPDATE",
            deviceId
        );

        List<Map<String, Object>> activeTrips = jdbc.queryForList(
            """
            SELECT trip_id
            FROM trip
            WHERE device_id = ?
              AND ended_at IS NULL
            LIMIT 1
            """,
            deviceId
        );

        if (!activeTrips.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "이미 진행 중인 이동이 있습니다."
            );
        }

        // trip 생성
        long tripId = insertAndGetId(
            """
            INSERT INTO trip (
                device_id,
                current_status,
                dest_name,
                started_at,
                expected_arrival_at,
                summary_shown,
                alt_routes_json
            )
            VALUES (?, 'MOVING', ?, ?, ?, FALSE, ?)
            """,
            deviceId,
            destName,
            nowTimestamp,
            expectedArrivalAt,
            alternativeRoutes.toString()
        );

        // 최초 이동 경로 저장
        long tripRouteId = insertAndGetId(
            """
            INSERT INTO trip_route (
                trip_id,
                source,
                evaluation_id,
                strategy_type,
                created_at
            )
            VALUES (?, 'INITIAL', NULL, ?, ?)
            """,
            tripId,
            strategyType,
            nowTimestamp
        );

        // 경로에 포함된 모든 구간 저장
        for (JsonNode segment : segments) {
            jdbc.update(
                """
                INSERT INTO segment (
                    trip_route_id,
                    seq,
                    mode,
                    gbis_route_id,
                    route_label,
                    board_station_id,
                    alight_station_id,
                    board_name,
                    alight_name,
                    match_status,
                    planned_start_at,
                    planned_end_at,
                    walk_min,
                    user_alight_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tripRouteId,
                requiredInt(segment, "seq"),
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

        // 현재 경로 지정
        jdbc.update(
            """
            UPDATE trip
            SET current_route_id = ?
            WHERE trip_id = ?
            """,
            tripRouteId,
            tripId
        );

        // 최초 이동 상태 기록
        jdbc.update(
            """
            INSERT INTO trip_status_log (
                trip_id,
                segment_id,
                status,
                plate_no,
                changed_at
            )
            VALUES (?, NULL, 'MOVING', NULL, ?)
            """,
            tripId,
            nowTimestamp
        );

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("tripId", tripId);
        response.put("currentRouteId", tripRouteId);
        response.put("currentStatus", "MOVING");
        response.put("destName", destName);
        response.put("strategyType", strategyType);
        response.put("startedAt", formatTime(nowTimestamp));
        response.put(
            "expectedArrivalAt",
            formatTime(expectedArrivalAt)
        );

        return response;
    }

    // 진행 중인 이동 조회 및 이어가기
    @Transactional(readOnly = true)
    public Map<String, Object> getActiveTrip(String deviceId) {
        validateDeviceId(deviceId);

        List<Map<String, Object>> activeTrips = jdbc.queryForList(
            """
            SELECT
                trip_id,
                current_route_id,
                current_status,
                dest_name,
                started_at,
                expected_arrival_at,
                last_suggested_at
            FROM trip
            WHERE device_id = ?
              AND ended_at IS NULL
            ORDER BY started_at DESC
            LIMIT 1
            """,
            deviceId
        );

        // 자동 종료 후 아직 확인하지 않은 이동 요약
        List<Map<String, Object>> pendingSummaries = jdbc.queryForList(
            """
            SELECT trip_id
            FROM trip
            WHERE device_id = ?
              AND end_type = 'AUTO'
              AND summary_shown = FALSE
            ORDER BY ended_at DESC
            LIMIT 1
            """,
            deviceId
        );

        Long pendingSummaryId = pendingSummaries.isEmpty()
            ? null
            : number(pendingSummaries.get(0).get("trip_id"));

        Map<String, Object> response = new LinkedHashMap<>();

        if (activeTrips.isEmpty()) {
            response.put("active", false);
            response.put("trip", null);
            response.put("pendingAutoSummaryTripId", pendingSummaryId);
            return response;
        }

        Map<String, Object> tripRow = activeTrips.get(0);

        long tripId = number(tripRow.get("trip_id"));
        long routeId = number(tripRow.get("current_route_id"));

        List<Map<String, Object>> routes = jdbc.queryForList(
            """
            SELECT
                trip_route_id,
                source,
                strategy_type
            FROM trip_route
            WHERE trip_route_id = ?
              AND trip_id = ?
            """,
            routeId,
            tripId
        );

        if (routes.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "현재 경로 정보가 존재하지 않습니다."
            );
        }

        Map<String, Object> routeRow = routes.get(0);

        List<Map<String, Object>> segmentRows = jdbc.queryForList(
            """
            SELECT
                segment_id,
                seq,
                mode,
                gbis_route_id,
                route_label,
                board_station_id,
                alight_station_id,
                board_name,
                alight_name,
                match_status,
                planned_start_at,
                planned_end_at,
                walk_min,
                user_alight_at
            FROM segment
            WHERE trip_route_id = ?
            ORDER BY seq
            """,
            routeId
        );

        List<Map<String, Object>> segments = new ArrayList<>();

        for (Map<String, Object> row : segmentRows) {
            Map<String, Object> segment = new LinkedHashMap<>();

            segment.put("segmentId", number(row.get("segment_id")));
            segment.put("seq", row.get("seq"));
            segment.put("mode", row.get("mode"));
            segment.put("gbisRouteId", row.get("gbis_route_id"));
            segment.put("routeLabel", row.get("route_label"));
            segment.put("boardStationId", row.get("board_station_id"));
            segment.put("alightStationId", row.get("alight_station_id"));
            segment.put("boardName", row.get("board_name"));
            segment.put("alightName", row.get("alight_name"));
            segment.put("matchStatus", row.get("match_status"));
            segment.put(
                "plannedStartAt",
                formatTime(row.get("planned_start_at"))
            );
            segment.put(
                "plannedEndAt",
                formatTime(row.get("planned_end_at"))
            );
            segment.put("walkMin", row.get("walk_min"));
            segment.put(
                "userAlightAt",
                formatTime(row.get("user_alight_at"))
            );

            segments.add(segment);
        }

        Map<String, Object> currentRoute = new LinkedHashMap<>();

        currentRoute.put("tripRouteId", routeId);
        currentRoute.put("source", routeRow.get("source"));
        currentRoute.put("strategyType", routeRow.get("strategy_type"));
        currentRoute.put("segments", segments);

        Map<String, Object> trip = new LinkedHashMap<>();

        trip.put("tripId", tripId);
        trip.put("currentStatus", tripRow.get("current_status"));
        trip.put("destName", tripRow.get("dest_name"));
        trip.put("startedAt", formatTime(tripRow.get("started_at")));
        trip.put(
            "expectedArrivalAt",
            formatTime(tripRow.get("expected_arrival_at"))
        );
        trip.put(
            "lastSuggestedAt",
            formatTime(tripRow.get("last_suggested_at"))
        );
        trip.put("currentRoute", currentRoute);

        response.put("active", true);
        response.put("trip", trip);
        response.put("pendingAutoSummaryTripId", pendingSummaryId);

        return response;
    }

    // 이동 상태 변경 및 기록
    @Transactional
    public Map<String, Object> changeStatus(
        String deviceId,
        long tripId,
        JsonNode request
    ) {
        validateDeviceId(deviceId);

        String nextStatus = requiredText(request, "status");

        if (!Set.of(
            "MOVING",
            "WAITING",
            "BOARDED",
            "BOARD_FAILED"
        ).contains(nextStatus)) {
            throw badRequest("올바르지 않은 이동 상태입니다.");
        }

        Long segmentId = optionalLong(request, "segmentId");
        String plateNo = optionalText(request, "plateNo");

        if (plateNo != null && plateNo.length() > 20) {
            throw badRequest("차량번호가 너무 깁니다.");
        }

        if (Set.of("BOARDED", "BOARD_FAILED").contains(nextStatus)
            && segmentId == null) {
            throw badRequest("탑승 결과의 구간 ID가 필요합니다.");
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
            """
            SELECT
                current_status,
                current_route_id,
                ended_at
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

        Map<String, Object> row = rows.get(0);

        if (row.get("ended_at") != null) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "이미 종료된 이동입니다."
            );
        }

        String previousStatus = (String) row.get("current_status");
        long currentRouteId = number(row.get("current_route_id"));

        // 중복 요청은 로그를 추가하지 않음
        if (previousStatus.equals(nextStatus)) {
            List<Map<String, Object>> lastLogs = jdbc.queryForList(
                """
                SELECT changed_at
                FROM trip_status_log
                WHERE trip_id = ?
                ORDER BY changed_at DESC, log_id DESC
                LIMIT 1
                """,
                tripId
            );

            Object lastChangedAt = lastLogs.isEmpty()
                ? null
                : lastLogs.get(0).get("changed_at");

            return statusResponse(
                tripId,
                previousStatus,
                nextStatus,
                segmentId,
                formatTime(lastChangedAt),
                false
            );
        }

        boolean allowed = switch (previousStatus) {
            case "MOVING" -> nextStatus.equals("WAITING");
            case "WAITING" ->
                nextStatus.equals("BOARDED")
                    || nextStatus.equals("BOARD_FAILED");
            case "BOARDED" -> nextStatus.equals("MOVING");
            default -> false;
        };

        if (!allowed) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "현재 상태에서 요청한 상태로 변경할 수 없습니다."
            );
        }

        // 구간 ID가 존재한다면 반드시 현재 경로에 속해야 함
        if (segmentId != null) {
            List<Map<String, Object>> matchedSegments = jdbc.queryForList(
                """
                SELECT segment_id
                FROM segment
                WHERE segment_id = ?
                  AND trip_route_id = ?
                LIMIT 1
                """,
                segmentId,
                currentRouteId
            );

            if (matchedSegments.isEmpty()) {
                throw badRequest(
                    "현재 경로에 속하지 않는 이동 구간입니다."
                );
            }
        }

        Timestamp changedAt = Timestamp.valueOf(
            currentLocalNow()
        );

        jdbc.update(
            """
            UPDATE trip
            SET current_status = ?
            WHERE trip_id = ?
            """,
            nextStatus,
            tripId
        );

        jdbc.update(
            """
            INSERT INTO trip_status_log (
                trip_id,
                segment_id,
                status,
                plate_no,
                changed_at
            )
            VALUES (?, ?, ?, ?, ?)
            """,
            tripId,
            segmentId,
            nextStatus,
            plateNo,
            changedAt
        );

        return statusResponse(
            tripId,
            previousStatus,
            nextStatus,
            segmentId,
            formatTime(changedAt),
            true
        );
    }

    // 공통 응답 생성
    private Map<String, Object> statusResponse(
        long tripId,
        String previousStatus,
        String currentStatus,
        Long segmentId,
        String changedAt,
        boolean reevaluationRequired
    ) {
        Map<String, Object> response = new LinkedHashMap<>();

        response.put("tripId", tripId);
        response.put("previousStatus", previousStatus);
        response.put("currentStatus", currentStatus);
        response.put("segmentId", segmentId);
        response.put("changedAt", changedAt);
        response.put(
            "reevaluationRequired",
            reevaluationRequired
        );
        response.put(
            "nextAction",
            reevaluationRequired ? "REEVALUATE" : "NONE"
        );

        return response;
    }

    // INSERT 후 MySQL AUTO_INCREMENT ID 반환
    private long insertAndGetId(String sql, Object... parameters) {
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                sql,
                Statement.RETURN_GENERATED_KEYS
            );

            for (int i = 0; i < parameters.length; i++) {
                ps.setObject(i + 1, parameters[i]);
            }

            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();

        if (key == null) {
            throw new IllegalStateException(
                "DB에서 생성된 ID를 가져오지 못했습니다."
            );
        }

        return key.longValue();
    }

    private void validateDeviceId(String deviceId) {
        try {
            UUID.fromString(deviceId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw badRequest("익명 기기 ID가 올바르지 않습니다.");
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);

        if (value == null
            || !value.isTextual()
            || value.asText().isBlank()) {
            throw badRequest(field + " 값이 필요합니다.");
        }

        return value.asText();
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);

        if (value == null || value.isNull()) {
            return null;
        }

        if (!value.isTextual()) {
            throw badRequest(field + " 형식이 올바르지 않습니다.");
        }

        return value.asText();
    }

    private int requiredInt(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);

        if (value == null || !value.isInt()) {
            throw badRequest(field + " 값이 올바르지 않습니다.");
        }

        return value.intValue();
    }

    private Integer optionalInt(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);

        if (value == null || value.isNull()) {
            return null;
        }

        if (!value.isInt()) {
            throw badRequest(field + " 형식이 올바르지 않습니다.");
        }

        return value.intValue();
    }

    private Long optionalLong(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);

        if (value == null || value.isNull()) {
            return null;
        }

        if (!value.isIntegralNumber() || value.longValue() <= 0) {
            throw badRequest(field + " 형식이 올바르지 않습니다.");
        }

        return value.longValue();
    }

    private Timestamp optionalTime(JsonNode node, String field) {
        String value = optionalText(node, field);

        if (value == null) {
            return null;
        }

        try {
            LocalDateTime dateTime = OffsetDateTime
                .parse(value)
                .atZoneSameInstant(KST)
                .toLocalDateTime();

            return Timestamp.valueOf(dateTime);
        } catch (Exception e) {
            throw badRequest(field + " 시각 형식이 올바르지 않습니다.");
        }
    }

    private String formatTime(Object value) {
        if (value == null) {
            return null;
        }

        LocalDateTime dateTime;

        if (value instanceof Timestamp timestamp) {
            dateTime = timestamp.toLocalDateTime();
        } else if (value instanceof LocalDateTime localDateTime) {
            dateTime = localDateTime;
        } else {
            throw new IllegalStateException(
                "지원하지 않는 DB 시각 형식입니다."
            );
        }

        return dateTime
            .atZone(KST)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private long number(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalStateException(
                "DB에서 ID를 조회할 수 없습니다."
            );
        }

        return number.longValue();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            message
        );
    }
}
