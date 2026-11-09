
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

class TripReevaluationReverseGuardTest {

    private static final String DEVICE_ID =
        "11111111-2222-4333-8444-555555555555";

    private static final long TRIP_ID = 1001L;
    private static final long CURRENT_ROUTE_ID = 202L;
    private static final long PREVIOUS_ROUTE_ID = 201L;
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
            jdbc, mapper, strategies
        );

        Map<String, Object> trip = new HashMap<>();
        trip.put("current_route_id", CURRENT_ROUTE_ID);
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
                      "gbisRouteId": "MOCK_PREVIOUS",
                      "routeLabel": "MOCK_PREVIOUS",
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

        when(jdbc.queryForList(
            anyString(), eq(CURRENT_ROUTE_ID)
        )).thenReturn(List.of(segment("MOCK_CURRENT")));

        when(jdbc.queryForList(
            anyString(), eq(PREVIOUS_ROUTE_ID)
        )).thenReturn(List.of(segment("MOCK_PREVIOUS")));

        when(jdbc.queryForList(
            anyString(),
            eq(Long.class),
            eq(TRIP_ID),
            eq(CURRENT_ROUTE_ID)
        )).thenReturn(List.of(PREVIOUS_ROUTE_ID));

        doAnswer(invocation -> {
            KeyHolder keyHolder = invocation.getArgument(1);

            keyHolder.getKeyList().add(
                Map.of("evaluation_id", 301L)
            );
            return 1;
        }).when(jdbc).update(
            any(PreparedStatementCreator.class),
            any(KeyHolder.class)
        );

        StrategyResponse comparison = new StrategyResponse(
            OffsetDateTime.now(KST),
            false,
            null,
            List.of(
                view(true, "MOCK_CURRENT", 30),
                view(false, "MOCK_PREVIOUS", 20)
            )
        );

        when(strategies.evaluate(
            any(RouteInput.class),
            anyList(),
            anyMap(),
            isNull(),
            any(OffsetDateTime.class),
            eq(Mode.REEVALUATION),
            any(OffsetDateTime.class)
        )).thenReturn(comparison);
    }

    @Test
    void doesNotRecommendReversingWithinFourMinutes() {
        setLastRouteChangeMinutesAgo(2);

        Map<String, Object> result = reevaluate();

        assertThat(result.get("result")).isEqualTo("KEEP");
        assertThat(result.get("suggestion")).isNull();
    }

    @Test
    void permitsSameComparisonAfterFourMinutes() {
        setLastRouteChangeMinutesAgo(5);

        Map<String, Object> result = reevaluate();

        assertThat(result.get("result")).isEqualTo("SUGGEST");
        assertThat(result.get("savedMinutes")).isEqualTo(10);
    }

    private void setLastRouteChangeMinutesAgo(long minutes) {
        when(jdbc.queryForList(
            anyString(),
            eq(TRIP_ID),
            eq(CURRENT_ROUTE_ID)
        )).thenReturn(List.of(Map.of(
            "source", "SUGGESTED",
            "created_at", Timestamp.valueOf(
                LocalDateTime.now(KST).minusMinutes(minutes)
            )
        )));
    }

    private Map<String, Object> reevaluate() {
        return service.reevaluate(
            DEVICE_ID,
            TRIP_ID,
            mapper.createObjectNode()
                .put("triggerType", "REFRESH")
        );
    }

    private Map<String, Object> segment(String busId) {
        LocalDateTime now = LocalDateTime.now(KST);
        Map<String, Object> result = new HashMap<>();

        result.put(
            "segment_id",
            busId.equals("MOCK_CURRENT") ? 401L : 402L
        );
        result.put("seq", 1);
        result.put("mode", "BUS");
        result.put("gbis_route_id", busId);
        result.put("route_label", busId);
        result.put("board_station_id", "MOCK_STOP");
        result.put("alight_station_id", "MOCK_DEST");
        result.put("board_name", "테스트 정류장");
        result.put("alight_name", "테스트 목적지");
        result.put("match_status", "MATCHED");

        result.put(
            "planned_start_at",
            Timestamp.valueOf(now.plusMinutes(5))
        );
        result.put(
            "planned_end_at",
            Timestamp.valueOf(now.plusMinutes(35))
        );

        result.put("walk_min", null);
        result.put("user_alight_at", null);

        return result;
    }

    private StrategyView view(
        boolean isCurrent,
        String busId,
        int minutes
    ) {
        TripSegment segment = new TripSegment(
            0,
            1,
            "BUS",
            busId,
            busId,
            "MOCK_STOP",
            "MOCK_DEST",
            "테스트 정류장",
            "테스트 목적지",
            "MATCHED",
            null,
            null,
            null,
            null
        );

        return new StrategyView(
            false,
            isCurrent,
            isCurrent
                ? StrategyType.KEEP
                : StrategyType.ALT_ROUTE,
            "모의 경로",
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
            OffsetDateTime.now(KST).plusMinutes(5),
            null,
            null,
            List.of("모의 도착정보"),
            new TripRoute(List.of(segment)),
            List.of()
        );
    }
}
