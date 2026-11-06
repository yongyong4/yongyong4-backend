
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
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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
    // 예상 도착 시각 + 30분 경과 시 종료
    // 1분 주기로 확인
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

        // MySQL DATETIME은 LocalDateTime으로 반환될 수 있음
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
                "아직 종료되지 않은 이동입니다."
            );
        }

        String endType = (String) trip.get("end_type");

        // DB 조회 결과를 안전하게 날짜 타입으로 변환
        LocalDateTime startedAt = asLocalDateTime(
            trip.get("started_at")
        );

        LocalDateTime endedAt = asLocalDateTime(
            trip.get("ended_at")
        );

        LocalDateTime nearDestAt = asLocalDateTime(
            trip.get("near_dest_at")
        );

        LocalDateTime expectedArrivalAt = asLocalDateTime(
            trip.get("expected_arrival_at")
        );

        // 사용자 종료: 종료 시각을 소요시간 계산에 사용
        // 자동 종료: 종료 시각을 실제 도착 시각으로 사용하지 않음
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

        Long boardingSuccessCount = countStatus(
            tripId, "BOARDED"
        );

        Long boardingFailureCount = countStatus(
            tripId, "BOARD_FAILED"
        );

        Long reevaluationCount = jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM evaluation
            WHERE trip_id = ?
              AND trigger_type <> 'INITIAL'
            """,
            Long.class,
            tripId
        );

        List<Map<String, Object>> routeRows = jdbc.queryForList(
            """
            SELECT
                tr.trip_route_id,
                tr.source,
                tr.strategy_type,
                tr.evaluation_id,
                tr.created_at
            FROM trip_route tr
            WHERE tr.trip_id = ?
              AND tr.source IN ('SUGGESTED', 'AFTER_FAIL')
            ORDER BY tr.created_at ASC, tr.trip_route_id ASC
            """,
            tripId
        );

        List<Map<String, Object>> routeChanges =
            new ArrayList<>();

        for (Map<String, Object> row : routeRows) {
            Map<String, Object> change =
                new LinkedHashMap<>();

            change.put(
                "tripRouteId",
                row.get("trip_route_id")
            );
            change.put(
                "evaluationId",
                row.get("evaluation_id")
            );
            change.put(
                "source",
                row.get("source")
            );
            change.put(
                "strategyType",
                row.get("strategy_type")
            );
            change.put(
                "changedAt",
                formatTime(row.get("created_at"))
            );

            routeChanges.add(change);
        }

        Map<String, Object> response =
            new LinkedHashMap<>();

        response.put("tripId", tripId);
        response.put("destName", trip.get("dest_name"));
        response.put("currentStatus", trip.get("current_status"));
        response.put("endType", endType);
        response.put("startedAt", formatTime(startedAt));
        response.put("endedAt", formatTime(endedAt));

        response.put(
            "expectedArrivalAt",
            formatTime(expectedArrivalAt)
        );

        response.put(
            "nearDestAt",
            formatTime(nearDestAt)
        );

        response.put("durationMin", durationMin);

        response.put(
            "boardingSuccessCount",
            boardingSuccessCount
        );

        response.put(
            "boardingFailureCount",
            boardingFailureCount
        );

        response.put(
            "reevaluationCount",
            reevaluationCount
        );

        response.put(
            "routeChangeCount",
            routeChanges.size()
        );

        response.put(
            "routeChanges",
            routeChanges
        );

        response.put(
            "summaryShown",
            toBoolean(trip.get("summary_shown"))
        );

        return response;
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

        // 중복 요청도 성공으로 처리
        jdbc.update(
            """
            UPDATE trip
            SET summary_shown = TRUE
            WHERE trip_id = ?
            """,
            tripId
        );

        Map<String, Object> response =
            new LinkedHashMap<>();

        response.put("tripId", tripId);
        response.put("summaryShown", true);

        return response;
    }

    // 기기 소유권 확인 및 이동 조회
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

    // 상태별 기록 횟수
    private Long countStatus(long tripId, String status) {
        return jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM trip_status_log
            WHERE trip_id = ?
              AND status = ?
            """,
            Long.class,
            tripId,
            status
        );
    }

    // 종료 처리 응답
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

    // 현재 시각
    private Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now(KST));
    }

    // MySQL DATETIME 조회 타입 대응
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

    // API용 날짜 문자열 변환
    private String formatTime(Object value) {
        LocalDateTime dateTime = asLocalDateTime(value);

        if (dateTime == null) {
            return null;
        }

        return dateTime
            .atZone(KST)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    // MySQL BOOLEAN 값 변환
    private boolean toBoolean(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }

        if (value instanceof Number number) {
            return number.intValue() != 0;
        }

        return false;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            message
        );
    }
}
