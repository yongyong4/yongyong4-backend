package com.yongyong.transit.trip;

import com.yongyong.transit.analysis.service.CallBudget;
import com.yongyong.transit.analysis.service.RouteAnalysisService.RouteInput;
import com.yongyong.transit.analysis.service.RouteAnalysisService.RouteSegment;
import com.yongyong.transit.strategy.service.StrategyService;
import com.yongyong.transit.strategy.service.StrategyService.Mode;
import com.yongyong.transit.strategy.service.StrategyService.StrategyResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

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

// 저장된 대안: 출발 당시 앞 도보 대신 지금 남은 구간을 붙인다
class TripReevaluationAlternativeWalkTest {

    private static final long TRIP_ID = 100L;
    private static final long ROUTE_ID = 200L;
    private static final String DEVICE_ID =
        "11111111-2222-4333-8444-555555555555";

    // 실제 정류장이나 노선이 아닌 테스트 전용 식별자
    private static final String BUS_ROUTE = "MOCK_CURRENT";
    private static final String STOP = "MOCK_STOP";
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
        service = new TripReevaluationService(jdbc, mapper, strategies);

        // 서비스는 초 단위로 자르므로 같은 기준으로 맞춘다
        LocalDateTime now = LocalDateTime.now(KST).withNano(0);

        Map<String, Object> trip = new HashMap<>();
        trip.put("trip_id", TRIP_ID);
        trip.put("current_route_id", ROUTE_ID);
        trip.put("current_status", "MOVING");
        trip.put("ended_at", null);
        // 같은 정류장(STOP)에서 타는 대안과 다른 정류장(OTHER_STOP)에서 타는 대안
        trip.put("alt_routes_json", """
            [
              {"route": {"segments": [
                {"mode": "WALK", "durationMin": 8},
                {"mode": "BUS", "durationMin": 20, "gbisRouteId": "MOCK_ALT",
                 "routeLabel": "MOCK_ALT", "boardStationId": "MOCK_STOP",
                 "alightStationId": "MOCK_DEST", "matchStatus": "MATCHED"}
              ]}},
              {"route": {"segments": [
                {"mode": "WALK", "durationMin": 5},
                {"mode": "BUS", "durationMin": 20, "gbisRouteId": "MOCK_OTHER",
                 "routeLabel": "MOCK_OTHER", "boardStationId": "OTHER_STOP",
                 "alightStationId": "MOCK_DEST", "matchStatus": "MATCHED"}
              ]}}
            ]
            """);
        when(jdbc.queryForList(anyString(), eq(TRIP_ID), eq(DEVICE_ID)))
            .thenReturn(List.of(trip));

        // 원래 계획: 8분 도보 → 버스
        Map<String, Object> walk = new HashMap<>();
        walk.put("segment_id", 401L);
        walk.put("seq", 1);
        walk.put("mode", "WALK");
        walk.put("planned_start_at", Timestamp.valueOf(now));
        walk.put("planned_end_at", Timestamp.valueOf(now.plusMinutes(8)));
        walk.put("walk_min", 8);

        Map<String, Object> bus = new HashMap<>();
        bus.put("segment_id", 402L);
        bus.put("seq", 2);
        bus.put("mode", "BUS");
        bus.put("gbis_route_id", BUS_ROUTE);
        bus.put("route_label", BUS_ROUTE);
        bus.put("board_station_id", STOP);
        bus.put("alight_station_id", "MOCK_DEST");
        bus.put("board_name", "모의 승차 정류장");
        bus.put("alight_name", "모의 하차 정류장");
        bus.put("match_status", "MATCHED");
        bus.put("planned_start_at", Timestamp.valueOf(now.plusMinutes(10)));
        bus.put("planned_end_at", Timestamp.valueOf(now.plusMinutes(40)));

        when(jdbc.queryForList(anyString(), eq(ROUTE_ID)))
            .thenReturn(List.of(walk, bus));
        when(jdbc.queryForList(anyString(), eq(TRIP_ID), eq(ROUTE_ID)))
            .thenReturn(List.of());

        // 3분 전에 걷기 시작 → 경과 시간 추정은 8 - 3 = 5분
        when(jdbc.queryForList(anyString(), eq(TRIP_ID)))
            .thenReturn(List.of(Map.of(
                "changed_at", Timestamp.valueOf(now.minusMinutes(3))
            )));

        doAnswer(invocation -> {
            KeyHolder holder = invocation.getArgument(1);
            holder.getKeyList().add(Map.of("evaluation_id", 300L));
            return 1;
        }).when(jdbc).update(
            any(PreparedStatementCreator.class),
            any(KeyHolder.class)
        );

        when(strategies.evaluate(
            any(RouteInput.class), anyList(), anyMap(), any(),
            any(OffsetDateTime.class), eq(Mode.REEVALUATION),
            any(OffsetDateTime.class), any(CallBudget.class)
        )).thenReturn(new StrategyResponse(
            OffsetDateTime.now(KST), false, null, List.of()
        ));
    }

    @Test
    void movingAlternativeStartsWithRemainingWalk() {
        List<RouteInput> alternatives = evaluatedAlternatives(request(false, 2));

        assertThat(alternatives).hasSize(1);
        List<RouteSegment> segments = alternatives.get(0).segments();
        assertThat(segments).extracting(RouteSegment::mode)
            .containsExactly("WALK", "BUS");
        assertThat(segments.get(0).durationMin()).isEqualTo(2);
        assertThat(segments).extracting(RouteSegment::segmentIndex)
            .containsExactly(0, 1);
    }

    @Test
    void alternativeAtCurrentStationHasNoWalk() {
        ObjectNode request = request(false, 2);
        request.put("currentStationId", "OTHER_STOP");

        List<RouteInput> alternatives = evaluatedAlternatives(request);

        // 원래 정류장 대안은 남은 도보를 붙이고, 지금 있는 정류장 대안은 버스부터
        assertThat(alternatives).hasSize(2);
        assertThat(alternatives.get(0).segments()).extracting(RouteSegment::mode)
            .containsExactly("WALK", "BUS");
        assertThat(alternatives.get(1).segments()).extracting(RouteSegment::mode)
            .containsExactly("BUS");
    }

    private ObjectNode request(boolean withLocation, Integer remainingWalkMin) {
        ObjectNode request = mapper.createObjectNode()
            .put("triggerType", "REFRESH");

        if (withLocation) {
            request.putObject("currentLocation")
                .put("x", 127.1)
                .put("y", 37.3);
        }
        if (remainingWalkMin != null) {
            request.put("remainingWalkMin", remainingWalkMin);
        }
        return request;
    }

    @SuppressWarnings("unchecked")
    private List<RouteInput> evaluatedAlternatives(ObjectNode request) {
        service.reevaluate(DEVICE_ID, TRIP_ID, request);

        ArgumentCaptor<List<RouteInput>> alternatives = ArgumentCaptor.forClass(List.class);
        verify(strategies, atLeastOnce()).evaluate(
            any(RouteInput.class), alternatives.capture(), anyMap(), any(),
            any(OffsetDateTime.class), eq(Mode.REEVALUATION),
            any(OffsetDateTime.class), any(CallBudget.class)
        );
        // 첫 호출이 현재 경로 + 저장된 대안 평가
        return alternatives.getAllValues().get(0);
    }
}
