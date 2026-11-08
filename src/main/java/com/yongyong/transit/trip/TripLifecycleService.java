
package com.yongyong.transit.trip;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@EnableScheduling
public class TripLifecycleService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int AUTO_END_DELAY_MIN = 30;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    public TripLifecycleService(
        JdbcTemplate jdbc,
        PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.transactionTemplate =
            new TransactionTemplate(transactionManager);
    }

    // 1. 이용자 이동 종료
    @Transactional
    public Map<String, Object> endTrip(
        String deviceId,
        long tripId,
        JsonNode request
    ) {
        JsonNode confirmed = request == null
            ? null
            : request.get("confirmed");

        if (confirmed == null
            || !confirmed.isBoolean()
            || !confirmed.asBoolean()) {
            throw badRequest("이동 종료 확인이 필요합니다.");
        }

        Map<String, Object> trip = findOwnedTrip(
            deviceId, tripId, true
        );

        Object previousEndedAt = trip.get("ended_at");
        String previousEndType = (String) trip.get("end_type");

        if (previousEndedAt != null) {
            if ("USER".equals(previousEndType)) {
                return endResponse(
                    tripId, "USER", previousEndedAt
                );
            }

            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "이미 자동 종료된 이동입니다."
            );
        }

        Timestamp endedAt = now();

        jdbc.update(
            """
            UPDATE trip
            SET current_status = 'ENDED',
                ended_at = ?,
                end_type = 'USER'
            WHERE trip_id = ?
              AND ended_at IS NULL
            """,
            endedAt,
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
            VALUES (?, NULL, 'ENDED', NULL, ?)
            """,
            tripId,
            endedAt
        );

        return endResponse(tripId, "USER", endedAt);
    }

    // 2. 이동 세션 자동 종료
    // 가장 최근 예상 도착 시각 + 30분
    // 1분마다 검사
    @Scheduled(fixedDelay = 60_000)
    public void autoEndOverdueTrips() {

        Timestamp threshold = Timestamp.valueOf(
            LocalDateTime.now(KST)
                .minusMinutes(AUTO_END_DELAY_MIN)
        );

        List<Long> targetIds = jdbc.queryForList(
            """
            SELECT trip_id
            FROM trip
            WHERE ended_at IS NULL
              AND expected_arrival_at IS NOT NULL
              AND expected_arrival_at <= ?
            ORDER BY expected_arrival_at ASC
            LIMIT 100
            """,
            Long.class,
            threshold
        );

        for (Long tripId : targetIds) {
            transactionTemplate.executeWithoutResult(
                status -> autoEndOneTrip(tripId)
            );
        }
    }

    private void autoEndOneTrip(long tripId) {

        List<Map<String, Object>> rows = jdbc.queryForList(
            """
            SELECT ended_at, expected_arrival_at
            FROM trip
            WHERE trip_id = ?
            FOR UPDATE
            """,
            tripId
        );

        if (rows.isEmpty()) {
            return;
        }

        Map<String, Object> trip = rows.get(0);

        if (trip.get("ended_at") != null) {
            return;
        }

        LocalDateTime expectedArrivalAt = asLocalDateTime(
            trip.get("expected_arrival_at")
        );

        if (expectedArrivalAt == null) {
            return;
        }

        LocalDateTime currentTime = LocalDateTime.now(KST);

        LocalDateTime deadline = expectedArrivalAt
            .plusMinutes(AUTO_END_DELAY_MIN);

        if (currentTime.isBefore(deadline)) {
            return;
        }

        Timestamp endedAt = Timestamp.valueOf(currentTime);

        jdbc.update(
            """
            UPDATE trip
            SET current_status = 'ENDED',
                ended_at = ?,
                end_type = 'AUTO',
                summary_shown = FALSE
            WHERE trip_id = ?
              AND ended_at IS NULL
            """,
            endedAt,
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
            VALUES (?, NULL, 'ENDED', NULL, ?)
            """,
            tripId,
            endedAt
        );
    }

    // 3. 이동 요약 조회
    // F-06-01 API 상세 명세 기준
    @Transactional(readOnly = true)
    public Map<String, Object> getSummary(
        String deviceId,
        long tripId
    ) {
        Map<String, Object> trip = findOwnedTrip(
            deviceId, tripId, false
        );

        if (trip.get("ended_at") == null) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "진행 중인 이동은 요약을 조회할 수 없습니다."
            );
        }

        String endType = (String) trip.get("end_type");

        LocalDateTime startedAt = asLocalDateTime(
            trip.get("started_at")
        );

        LocalDateTime endedAt = asLocalDateTime(
            trip.get("ended_at")
        );

        LocalDateTime nearDestAt = asLocalDateTime(
            trip.get("near_dest_at")
        );

        // USER: 실제 종료 시각
        // AUTO: 목적지 근처 도착 시각이 있을 때만 계산
        LocalDateTime arrivalBasis = null;

        if ("USER".equals(endType)) {
            arrivalBasis = endedAt;
        } else if ("AUTO".equals(endType)) {
            arrivalBasis = nearDestAt;
        }

        Long durationMin = null;

        if (startedAt != null && arrivalBasis != null) {
            long minutes = ChronoUnit.MINUTES.between(
                startedAt,
                arrivalBasis
            );

            if (minutes >= 0) {
                durationMin = minutes;
            }
        }

        List<Map<String, Object>> boardingResults =
            getBoardingResults(tripId);

        List<Map<String, Object>> routeChangeHistory =
            getRouteChangeHistory(tripId);

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("tripId", tripId);
        response.put("destName", trip.get("dest_name"));
        response.put("currentStatus", trip.get("current_status"));
        response.put("endType", endType);
        response.put("startedAt", formatTime(startedAt));
        response.put("endedAt", formatTime(endedAt));
        response.put("nearDestAt", formatTime(nearDestAt));
        response.put("durationMin", durationMin);
        response.put(
            "summaryShown",
            toBoolean(trip.get("summary_shown"))
        );
        response.put("boardingResults", boardingResults);
        response.put("routeChangeHistory", routeChangeHistory);

        return response;
    }

    // 3-1. 버스 구간별 탑승 성공·실패 기록
    // 최초 경로와 변경 경로의 모든 버스 구간 조회
    private List<Map<String, Object>> getBoardingResults(
        long tripId
    ) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            """
            SELECT
                s.segment_id,
                s.route_label,
                l.log_id,
                l.status AS boarding_status,
                l.changed_at
            FROM trip_route tr
            JOIN segment s
              ON s.trip_route_id = tr.trip_route_id
            LEFT JOIN trip_status_log l
              ON l.segment_id = s.segment_id
             AND l.trip_id = tr.trip_id
             AND l.status IN ('BOARDED', 'BOARD_FAILED')
            WHERE tr.trip_id = ?
              AND s.mode = 'BUS'
            ORDER BY
                tr.created_at ASC,
                tr.trip_route_id ASC,
                s.seq ASC,
                l.changed_at ASC,
                l.log_id ASC
            """,
            tripId
        );

        // 같은 구간에 여러 탑승 기록이 있어도
        // boardingResults에는 구간을 한 번만 포함
        Map<Long, Map<String, Object>> segmentMap =
            new LinkedHashMap<>();

        for (Map<String, Object> row : rows) {

            long segmentId = toLong(
                row.get("segment_id")
            );

            Map<String, Object> boardingResult =
                segmentMap.get(segmentId);

            if (boardingResult == null) {
                boardingResult = new LinkedHashMap<>();

                boardingResult.put(
                    "segmentId",
                    segmentId
                );

                boardingResult.put(
                    "routeLabel",
                    row.get("route_label")
                );

                boardingResult.put(
                    "events",
                    new ArrayList<Map<String, Object>>()
                );

                segmentMap.put(segmentId, boardingResult);
            }

            String status =
                (String) row.get("boarding_status");

            // 기록이 없는 버스 구간은 events: []
            if (status == null) {
                continue;
            }

            Map<String, Object> event =
                new LinkedHashMap<>();

            event.put("status", status);
            event.put(
                "changedAt",
                formatTime(row.get("changed_at"))
            );

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events =
                (List<Map<String, Object>>)
                    boardingResult.get("events");

            events.add(event);
        }

        return new ArrayList<>(segmentMap.values());
    }

    // 3-2. 경로 변경 제안·수락·무시·미응답 이력
    private List<Map<String, Object>> getRouteChangeHistory(
        long tripId
    ) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            """
            SELECT
                e.evaluation_id,
                e.evaluated_at,
                e.suggested_strategy,
                e.accepted,
                e.responded_at,
                CASE
                    WHEN e.accepted = TRUE THEN (
                        SELECT MIN(tr.trip_route_id)
                        FROM trip_route tr
                        WHERE tr.trip_id = e.trip_id
                          AND tr.evaluation_id = e.evaluation_id
                          AND tr.source IN (
                              'SUGGESTED',
                              'AFTER_FAIL'
                          )
                    )
                    ELSE NULL
                END AS applied_route_id
            FROM evaluation e
            WHERE e.trip_id = ?
              AND e.result = 'SUGGEST'
            ORDER BY
                e.evaluated_at ASC,
                e.evaluation_id ASC
            """,
            tripId
        );

        List<Map<String, Object>> history =
            new ArrayList<>();

        for (Map<String, Object> row : rows) {

            Map<String, Object> item =
                new LinkedHashMap<>();

            item.put(
                "evaluationId",
                toLong(row.get("evaluation_id"))
            );

            item.put(
                "suggestedAt",
                formatTime(row.get("evaluated_at"))
            );

            item.put(
                "strategyType",
                row.get("suggested_strategy")
            );

            Object acceptedValue = row.get("accepted");

            // 미응답 평가: accepted = null
            item.put(
                "accepted",
                acceptedValue == null
                    ? null
                    : toBoolean(acceptedValue)
            );

            item.put(
                "respondedAt",
                formatTime(row.get("responded_at"))
            );

            Object appliedRouteId =
                row.get("applied_route_id");

            item.put(
                "appliedRouteId",
                appliedRouteId == null
                    ? null
                    : toLong(appliedRouteId)
            );

            history.add(item);
        }

        return history;
    }

    // 4. 이동 요약 확인 완료
    @Transactional
    public Map<String, Object> markSummarySeen(
        String deviceId,
        long tripId,
        JsonNode request
    ) {
        JsonNode seen = request == null
            ? null
            : request.get("seen");

        if (seen == null
            || !seen.isBoolean()
            || !seen.asBoolean()) {
            throw badRequest("요약 확인 값이 올바르지 않습니다.");
        }

        Map<String, Object> trip = findOwnedTrip(
            deviceId, tripId, true
        );

        if (trip.get("ended_at") == null) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "종료되지 않은 이동의 요약은 확인할 수 없습니다."
            );
        }

        // 중복 요청 허용
        jdbc.update(
            """
            UPDATE trip
            SET summary_shown = TRUE
            WHERE trip_id = ?
            """,
            tripId
        );

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("tripId", tripId);
        response.put("summaryShown", true);

        return response;
    }

    // 5. 기기 소유권 및 이동 세션 조회
    private Map<String, Object> findOwnedTrip(
        String deviceId,
        long tripId,
        boolean lock
    ) {
        if (deviceId == null || deviceId.isBlank()) {
            throw badRequest("기기 ID가 필요합니다.");
        }

        String sql = """
                SELECT
                    trip_id,
                    device_id,
                    current_status,
                    dest_name,
                    started_at,
                    ended_at,
                    end_type,
                    near_dest_at,
                    expected_arrival_at,
                    summary_shown
                FROM trip
                WHERE trip_id = ?
                  AND device_id = ?
                """ + (lock ? " FOR UPDATE" : "");

        List<Map<String, Object>> rows =
            jdbc.queryForList(sql, tripId, deviceId);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "이동 세션을 찾을 수 없습니다."
            );
        }

        return rows.get(0);
    }

    // 6. 공통 유틸리티
    private Map<String, Object> endResponse(
        long tripId,
        String endType,
        Object endedAt
    ) {
        Map<String, Object> result =
            new LinkedHashMap<>();

        result.put("tripId", tripId);
        result.put("currentStatus", "ENDED");
        result.put("endType", endType);
        result.put("endedAt", formatTime(endedAt));

        return result;
    }

    private Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now(KST));
    }

    // MySQL DATETIME은 LocalDateTime으로 반환될 수 있음
    private LocalDateTime asLocalDateTime(Object value) {

        if (value == null) {
            return null;
        }

        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }

        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }

        throw new IllegalStateException(
            "지원하지 않는 DB 날짜 타입: "
                + value.getClass().getName()
        );
    }

    private String formatTime(Object value) {

        LocalDateTime dateTime = asLocalDateTime(value);

        if (dateTime == null) {
            return null;
        }

        return dateTime
            .atZone(KST)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private boolean toBoolean(Object value) {

        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }

        if (value instanceof Number number) {
            return number.intValue() != 0;
        }

        return false;
    }

    private long toLong(Object value) {

        if (value instanceof Number number) {
            return number.longValue();
        }

        throw new IllegalStateException(
            "DB ID 값의 형식이 올바르지 않습니다."
        );
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            message
        );
    }
}
