
package com.yongyong.transit.trip;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TripDecisionServiceSafetyTest {

    private static final long TRIP_ID = 1001L;
    private static final long CURRENT_ROUTE_ID = 202L;
    private static final long OLD_ROUTE_ID = 201L;
    private static final long EVALUATION_ID = 301L;
    private static final String DEVICE_ID =
        "11111111-2222-4333-8444-555555555555";

    private JdbcTemplate jdbc;
    private JsonMapper mapper;
    private TripDecisionService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        mapper = JsonMapper.builder().build();
        service = new TripDecisionService(jdbc, mapper);
    }

    @Test
    void outdatedSuggestionCannotBeAccepted() {
        stubTripAndEvaluation(OLD_ROUTE_ID);

        assertThatThrownBy(() -> decide(true))
            .isInstanceOfSatisfying(
                ResponseStatusException.class,
                error -> assertThat(error.getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT)
            );

        verify(jdbc, never()).update(
            any(PreparedStatementCreator.class),
            any(KeyHolder.class)
        );
    }

    @Test
    void outdatedSuggestionCannotChangeWaitingStatusOnIgnore() {
        stubTripAndEvaluation(OLD_ROUTE_ID);

        assertThatThrownBy(() -> decide(false))
            .isInstanceOfSatisfying(
                ResponseStatusException.class,
                error -> assertThat(error.getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT)
            );
    }

    @Test
    void ignoreCurrentSuggestionKeepsExistingRoute() {
        stubTripAndEvaluation(CURRENT_ROUTE_ID);

        Map<String, Object> result = decide(false);

        assertThat(result.get("accepted")).isEqualTo(false);
        assertThat(result.get("currentRouteId"))
            .isEqualTo(CURRENT_ROUTE_ID);
        assertThat(result.get("currentStatus"))
            .isEqualTo("WAITING");

        verify(jdbc).update(
            contains("UPDATE evaluation"),
            any(Timestamp.class),
            eq(EVALUATION_ID)
        );

        verify(jdbc, never()).update(
            any(PreparedStatementCreator.class),
            any(KeyHolder.class)
        );
    }

    private Map<String, Object> decide(boolean accepted) {
        JsonNode request = mapper.createObjectNode()
            .put("accepted", accepted);

        return service.decide(
            DEVICE_ID,
            TRIP_ID,
            EVALUATION_ID,
            request
        );
    }

    private void stubTripAndEvaluation(long originRouteId) {
        Map<String, Object> trip = new HashMap<>();
        trip.put("trip_id", TRIP_ID);
        trip.put("current_route_id", CURRENT_ROUTE_ID);
        trip.put("current_status", "WAITING");
        trip.put("ended_at", null);

        Map<String, Object> evaluation = new HashMap<>();
        evaluation.put("evaluation_id", EVALUATION_ID);
        evaluation.put("result", "SUGGEST");
        evaluation.put("suggested_strategy", "OTHER_MODE");
        evaluation.put("accepted", null);
        evaluation.put("responded_at", null);

        evaluation.put("suggestion_json", """
            {
              "originRouteId": %d,
              "strategyType": "OTHER_MODE",
              "canAccept": true,
              "route": {
                "segments": [
                  {
                    "mode": "WALK",
                    "matchStatus": "NA",
                    "walkMin": 15
                  }
                ]
              }
            }
            """.formatted(originRouteId));

        when(jdbc.queryForList(
            anyString(), eq(TRIP_ID), eq(DEVICE_ID)
        )).thenReturn(List.of(trip));

        when(jdbc.queryForList(
            anyString(), eq(EVALUATION_ID), eq(TRIP_ID)
        )).thenReturn(List.of(evaluation));
    }
}
