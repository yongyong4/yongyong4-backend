
package com.yongyong.transit.trip;

import com.yongyong.transit.analysis.client.GbisClient;
import com.yongyong.transit.analysis.client.GbisClient.GbisArrivals;
import com.yongyong.transit.analysis.client.GbisClient.GbisBus;
import com.yongyong.transit.analysis.client.PredictClient;
import com.yongyong.transit.analysis.service.BusRealtimeService;
import com.yongyong.transit.analysis.service.BusRealtimeService.CollectorSnapshot;
import com.yongyong.transit.analysis.service.CallBudget;
import com.yongyong.transit.analysis.service.GbisCallLimiter;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.BusArrival;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SeatType;
import com.yongyong.transit.collector.client.CollectorClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TripReevaluationGbisFallbackTest {

    private static final String STATION = "MOCK_STATION";
    private static final String ROUTE = "MOCK_ROUTE";

    private final OffsetDateTime now =
        OffsetDateTime.now(ZoneOffset.ofHours(9)).withNano(0);

    private GbisClient gbis;
    private BusRealtimeService service;

    @BeforeEach
    void setUp() {
        gbis = mock(GbisClient.class);

        GbisCallLimiter limiter = mock(GbisCallLimiter.class);
        CollectorClient collector = mock(CollectorClient.class);
        PredictClient predictor = mock(PredictClient.class);

        service = new BusRealtimeService(
            gbis, limiter, collector, predictor
        );

        when(gbis.hasKey()).thenReturn(true);

        when(limiter.tryAcquire(
            eq(GbisCallLimiter.ARRIVAL),
            any(LocalDate.class)
        )).thenReturn(true);

        when(gbis.getStationArrivals(anyString()))
            .thenReturn(new GbisArrivals(
                now,
                List.of(new GbisBus(
                    ROUTE,
                    ROUTE,
                    "MOCK_PLATE",
                    180,
                    10
                ))
            ));
    }

    @Test
    void usesGbisWhenCollectorDataIsMissing() {
        CallBudget budget = CallBudget.of(1);

        var result = service.getArrivals(
            STATION,
            Set.of(ROUTE),
            CollectorSnapshot.empty(),
            now,
            budget
        );

        assertThat(result.arrivals()).hasSize(1);
        assertThat(result.arrivals().get(0).routeId())
            .isEqualTo(ROUTE);

        assertThat(budget.used()).isEqualTo(1);

        verify(gbis, times(1))
            .getStationArrivals(STATION);
    }

    @Test
    void doesNotCallGbisWhenCollectorHasFreshArrival() {
        BusArrival arrival = new BusArrival(
            ROUTE,
            ROUTE,
            "COLLECTOR_PLATE",
            now.plusMinutes(3),
            10,
            SeatType.OBSERVED,
            now,
            null
        );

        CollectorSnapshot snapshot = new CollectorSnapshot(
            Map.of(STATION, List.of(arrival)),
            Set.of(ROUTE)
        );

        CallBudget budget = CallBudget.of(1);

        var result = service.getArrivals(
            STATION,
            Set.of(ROUTE),
            snapshot,
            now,
            budget
        );

        assertThat(result.arrivals()).hasSize(1);
        assertThat(budget.used()).isZero();

        verify(gbis, never())
            .getStationArrivals(anyString());
    }

    @Test
    void sharedBudgetLimitsGbisCallsAcrossStations() {
        CallBudget budget = CallBudget.of(1);

        service.getArrivals(
            STATION,
            Set.of(ROUTE),
            CollectorSnapshot.empty(),
            now,
            budget
        );

        var second = service.getArrivals(
            "MOCK_SECOND_STATION",
            Set.of(ROUTE),
            CollectorSnapshot.empty(),
            now,
            budget
        );

        assertThat(budget.used()).isEqualTo(1);
        assertThat(second.arrivals()).isEmpty();
        assertThat(second.delayed()).isTrue();

        verify(gbis, times(1))
            .getStationArrivals(anyString());
    }
}
