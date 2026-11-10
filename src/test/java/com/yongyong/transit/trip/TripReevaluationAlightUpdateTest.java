package com.yongyong.transit.trip;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TripReevaluationAlightUpdateTest {

    private static final long ROUTE_ID = 21L;
    private static final long SEGMENT_ID = 11L;

    private static final OffsetDateTime NOW =
        OffsetDateTime.parse("2026-11-10T14:00:00+09:00");

    private JdbcTemplate jdbc;
    private JsonMapper mapper;
    private TripReevaluationService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        mapper = JsonMapper.builder().build();

        service = new TripReevaluationService(
            jdbc,
            mapper,
            mock(com.yongyong.transit.strategy.service.StrategyService.class)
        );
    }

    @Test
    void savesValidSubwayAlightTime() throws Exception {
        List<?> legs = loadLegs("SUBWAY");

        JsonNode request = mapper.readTree("""
            {
              "alightUpdates": [
                {
                  "segmentId": 11,
                  "userAlightAt": "2026-11-10T05:30:00Z"
                }
              ]
            }
            """);

        Boolean applied = ReflectionTestUtils.invokeMethod(
            service,
            "applyAlightUpdates",
            request,
            ROUTE_ID,
            legs,
            NOW
        );

        assertThat(applied).isTrue();

        verify(jdbc).update(
            contains("SET user_alight_at = ?"),
            eq(Timestamp.valueOf(
                LocalDateTime.of(2026, 11, 10, 14, 30)
            )),
            eq(SEGMENT_ID),
            eq(ROUTE_ID)
        );
    }

    @Test
    void rejectsNonSubwaySegment() throws Exception {
        List<?> legs = loadLegs("BUS");

        JsonNode request = mapper.readTree("""
            {
              "alightUpdates": [
                {
                  "segmentId": 11,
                  "userAlightAt": "2026-11-10T14:30:00+09:00"
                }
              ]
            }
            """);

        assertThatThrownBy(() ->
            ReflectionTestUtils.invokeMethod(
                service,
                "applyAlightUpdates",
                request,
                ROUTE_ID,
                legs,
                NOW
            )
        ).isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST)
        );

        verify(jdbc, never()).update(
            anyString(),
            any(Timestamp.class),
            anyLong(),
            anyLong()
        );
    }

    @Test
    void rejectsPastAlightTime() throws Exception {
        List<?> legs = loadLegs("SUBWAY");

        JsonNode request = mapper.readTree("""
            {
              "alightUpdates": [
                {
                  "segmentId": 11,
                  "userAlightAt": "2026-11-10T13:30:00+09:00"
                }
              ]
            }
            """);

        assertThatThrownBy(() ->
            ReflectionTestUtils.invokeMethod(
                service,
                "applyAlightUpdates",
                request,
                ROUTE_ID,
                legs,
                NOW
            )
        ).isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST)
        );

        verify(jdbc, never()).update(
            anyString(),
            any(Timestamp.class),
            anyLong(),
            anyLong()
        );
    }

    private List<?> loadLegs(String mode) {
        Map<String, Object> row = new HashMap<>();

        row.put("segment_id", SEGMENT_ID);
        row.put("seq", 1);
        row.put("mode", mode);

        when(jdbc.queryForList(
            anyString(),
            eq(ROUTE_ID)
        )).thenReturn(List.of(row));

        return ReflectionTestUtils.invokeMethod(
            service,
            "readLegs",
            ROUTE_ID
        );
    }
}