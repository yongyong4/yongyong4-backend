
package com.yongyong.transit.trip;

import com.yongyong.transit.analysis.service.RouteAnalysisService.RouteInput;
import com.yongyong.transit.strategy.service.StrategyService;
import com.yongyong.transit.strategy.service.StrategyService.Mode;
import com.yongyong.transit.strategy.service.StrategyService.StrategyResponse;
import com.yongyong.transit.strategy.service.StrategyService.StrategyType;
import com.yongyong.transit.strategy.service.StrategyService.StrategyView;
import com.yongyong.transit.strategy.service.StrategyService.TripRoute;
import com.yongyong.transit.strategy.service.StrategyService.TripSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TripReevaluationServiceTest {

    private static final long TRIP_ID = 100L;
    private static final long ROUTE_ID = 200L;
    private static final long EVALUATION_ID = 300L;

    private static final String DEVICE_ID =
        "11111111-2222-4333-8444-555555555555";

    // 실제 정류장이나 노선이 아닌 테스트 전용 식별자
    private static final String STOP = "MOCK_STOP";
    private static final String DEST = "MOCK_DEST";

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private JdbcTemplate jdbc;
    private JsonMapper mapper;
    private StrategyService strategies;
    private TripReevaluationService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        mapper = JsonMapper.builder().build();
        strategies = mock(StrategyService.class);

        service = new TripReevaluationService(
            jdbc,
            mapper,
            strategies
        );

        Map<String, Object> trip = new HashMap<>();
        trip.put("trip_id", TRIP_ID);
        trip.put("current_route_id", ROUTE_ID);
        trip.put("current_status", "WAITING");
        trip.put("ended_at", null);

        trip.put("alt_routes_json", """
            [
              {
                "route": {
                  "segments": [
                    {
                      "mode": "BUS",
                      "durationMin": 20,
                      "gbisRouteId": "MOCK_ALT",
                      "routeLabel": "MOCK_ALT",
                      "boardStationId": "MOCK_STOP",
                      "alightStationId": "MOCK_DEST",
                      "matchStatus": "MATCHED"
                    }
                  ]
                }
              }
            ]
            """);

        when(jdbc.queryForList(
            anyString(), eq(TRIP_ID), eq(DEVICE_ID)
        )).thenReturn(List.of(trip));

        LocalDateTime now = LocalDateTime.now(KST);

        Map<String, Object> segment = new HashMap<>();
        segment.put("segment_id", 400L);
        segment.put("seq", 1);
        segment.put("mode", "BUS");
        segment.put("gbis_route_id", "MOCK_CURRENT");
        segment.put("route_label", "MOCK_CURRENT");
        segment.put("board_station_id", STOP);
        segment.put("alight_station_id", DEST);
        segment.put("board_name", "모의 승차 정류장");
        segment.put("alight_name", "모의 하차 정류장");
        segment.put("match_status", "MATCHED");
        segment.put(
            "planned_start_at",
            Timestamp.valueOf(now.plusMinutes(5))
        );
        segment.put(
            "planned_end_at",
            Timestamp.valueOf(now.plusMinutes(35))
        );
        segment.put("walk_min", null);
        segment.put("user_alight_at", null);

        when(jdbc.queryForList(
            anyString(), eq(ROUTE_ID)
        )).thenReturn(List.of(segment));

        when(jdbc.queryForList(
            anyString(), eq(TRIP_ID), eq(ROUTE_ID)
        )).thenReturn(List.of());

        // 실제 INSERT 없이 평가 ID 생성만 모의 처리
        doAnswer(invocation -> {
            KeyHolder holder = invocation.getArgument(1);
            holder.getKeyList().add(
                Map.of("evaluation_id", EVALUATION_ID)
            );
            return 1;
        }).when(jdbc).update(
            any(PreparedStatementCreator.class),
            any(KeyHolder.class)
        );
    }

    // 1. 정확히 3분 절약하면 제안하지 않아야 함
    @Test
    void threeMinutesSavedKeepsCurrentRoute() {
        mockStrategyResult(30, 27, true, true);

        Map<String, Object> result = reevaluate();

        assertThat(result.get("result")).isEqualTo("KEEP");
        assertThat(result.get("currentValueMin")).isEqualTo(30);
        assertThat(result.get("suggestedValueMin")).isNull();
        assertThat(result.get("savedMinutes")).isNull();
        assertThat(result.get("suggestion")).isNull();
    }

    // 2. 4분 절약하면 자동으로 SUGGEST 생성
    @Test
    void fourMinutesSavedGeneratesSuggestion() {
        mockStrategyResult(30, 26, true, true);

        Map<String, Object> result = reevaluate();

        assertThat(result.get("result")).isEqualTo("SUGGEST");
        assertThat(result.get("currentValueMin")).isEqualTo(30);
        assertThat(result.get("suggestedValueMin")).isEqualTo(26);
        assertThat(result.get("savedMinutes")).isEqualTo(4);

        JsonNode suggestion = (JsonNode) result.get("suggestion");

        assertThat(suggestion).isNotNull();
        assertThat(suggestion.path("canAccept").asBoolean()).isTrue();
        assertThat(suggestion.path("savedMinutes").asInt()).isEqualTo(4);
        assertThat(suggestion.path("originRouteId").asLong())
            .isEqualTo(ROUTE_ID);

        assertThat(suggestion.path("route")
            .path("segments").isArray()).isTrue();

        assertThat(suggestion.path("route")
            .path("segments").size()).isGreaterThan(0);
    }

    // 3. 대안의 실시간 버스 도착 근거가 없으면 추천 금지
    @Test
    void missingArrivalEvidenceDoesNotSuggest() {
        // 계산상 10분 절약이지만 대안 도착정보는 없음
        mockStrategyResult(30, 20, true, false);

        Map<String, Object> result = reevaluate();

        assertThat(result.get("result")).isEqualTo("KEEP");
        assertThat(result.get("suggestion")).isNull();
        assertThat(result.get("savedMinutes")).isNull();
    }

    private Map<String, Object> reevaluate() {
        JsonNode request = mapper.createObjectNode()
            .put("triggerType", "REFRESH");

        Map<String, Object> result = service.reevaluate(
            DEVICE_ID,
            TRIP_ID,
            request
        );

        // 기능 3 공통 전략 비교 로직을 재평가 모드로 호출했는지 확인
        verify(strategies, atLeastOnce()).evaluate(
            any(RouteInput.class),
            anyList(),
            anyMap(),
            isNull(),
            any(OffsetDateTime.class),
            eq(Mode.REEVALUATION),
            any(OffsetDateTime.class),
            any(com.yongyong.transit.analysis.service.CallBudget.class)
        );

        assertThat(result.get("evaluationId"))
            .isEqualTo(EVALUATION_ID);

        return result;
    }

    private void mockStrategyResult(
        int currentMinutes,
        int alternativeMinutes,
        boolean currentHasArrival,
        boolean alternativeHasArrival
    ) {
        StrategyView current = createView(
            true,
            currentMinutes,
            currentHasArrival
        );

        StrategyView alternative = createView(
            false,
            alternativeMinutes,
            alternativeHasArrival
        );

        StrategyResponse response = new StrategyResponse(
            OffsetDateTime.now(KST),
            false,
            null,
            List.of(current, alternative)
        );

        when(strategies.evaluate(
            any(RouteInput.class),
            anyList(),
            anyMap(),
            isNull(),
            any(OffsetDateTime.class),
            eq(Mode.REEVALUATION),
            any(OffsetDateTime.class),
            any(com.yongyong.transit.analysis.service.CallBudget.class)
        )).thenReturn(response);
    }

    private StrategyView createView(
        boolean current,
        int minutes,
        boolean hasArrival
    ) {
        String busId = current
            ? "MOCK_CURRENT"
            : "MOCK_ALT";

        TripSegment segment = new TripSegment(
            0,
            1,
            "BUS",
            busId,
            busId,
            STOP,
            DEST,
            "모의 승차 정류장",
            "모의 하차 정류장",
            "MATCHED",
            null,
            null,
            null,
            null
        );

        OffsetDateTime arrival = hasArrival
            ? OffsetDateTime.now(KST).plusMinutes(5)
            : null;

        return new StrategyView(
            false,
            current,
            current ? StrategyType.KEEP : StrategyType.ALT_ROUTE,
            current ? "현재 경로" : "모의 대안",
            null,
            null,
            null,
            0,
            null,
            minutes,
            minutes,
            minutes,
            null,
            null,
            null,
            arrival,
            null,
            null,
            List.of("모의 테스트"),
            new TripRoute(List.of(segment)),
            List.of()
        );
    }
}
