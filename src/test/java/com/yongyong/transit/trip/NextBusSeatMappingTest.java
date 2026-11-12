
package com.yongyong.transit.trip;

import com.yongyong.transit.analysis.service.RouteAnalysisService.SegmentView;
import com.yongyong.transit.analysis.service.SegmentAnalyzer;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.BusArrival;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SeatType;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SegmentInput;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SegmentResult;
import com.yongyong.transit.strategy.service.StrategyService.StrategyView;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NextBusSeatMappingTest {

    private final SegmentAnalyzer analyzer = new SegmentAnalyzer();

    private static final OffsetDateTime NOW =
        OffsetDateTime.parse("2026-11-12T07:00:00+09:00");

    @Test
    void analysisPreservesNextBusSeatInformation() {
        SegmentResult result = analyzeWithNextBus(3);

        assertEquals(12, result.remainSeats());
        assertEquals(SeatType.PREDICTED, result.seatType());
        assertEquals(3, result.nextBusRemainSeats());
        assertEquals(SeatType.OBSERVED, result.nextBusSeatType());

        SegmentView view = SegmentView.of(result);

        assertEquals(12, view.remainSeats());
        assertEquals(3, view.nextBusRemainSeats());
        assertEquals(SeatType.OBSERVED, view.nextBusSeatType());

        SegmentView copied = view.withSegmentIndex(5);

        assertEquals(5, copied.segmentIndex());
        assertEquals(3, copied.nextBusRemainSeats());
        assertEquals(SeatType.OBSERVED, copied.nextBusSeatType());
    }

    @Test
    void reevaluationSeparatesCurrentAndNextBusSeats()
        throws Exception {

        SegmentView view = SegmentView.of(analyzeWithNextBus(3));
        Map<String, Object> response = nextSegment(view);

        assertNotNull(response);
        assertEquals(12, response.get("remainSeats"));
        assertEquals(SeatType.PREDICTED, response.get("seatType"));

        assertEquals(3, response.get("nextBusRemainSeats"));
        assertEquals(
            SeatType.OBSERVED,
            response.get("nextBusSeatType")
        );
    }

    @Test
    void zeroRemainingSeatsIsNotMissingData()
        throws Exception {

        SegmentView view = SegmentView.of(analyzeWithNextBus(0));
        Map<String, Object> response = nextSegment(view);

        assertNotNull(response);
        assertEquals(0, response.get("nextBusRemainSeats"));
        assertEquals(
            SeatType.OBSERVED,
            response.get("nextBusSeatType")
        );
    }

    @Test
    void missingNextBusReturnsNullSeatInformation()
        throws Exception {

        SegmentView view = SegmentView.of(
            analyze(false, null)
        );

        Map<String, Object> response = nextSegment(view);

        assertNotNull(response);
        assertEquals(12, response.get("remainSeats"));
        assertNull(response.get("nextBusRemainSeats"));
        assertNull(response.get("nextBusSeatType"));
    }

    private SegmentResult analyzeWithNextBus(int nextSeats) {
        return analyze(true, nextSeats);
    }

    private SegmentResult analyze(
        boolean hasNextBus,
        Integer nextSeats
    ) {
        List<BusArrival> arrivals = new ArrayList<>();

        arrivals.add(new BusArrival(
            "20001",
            "5000",
            "BUS-001",
            NOW.plusMinutes(6),
            12,
            SeatType.PREDICTED,
            NOW,
            "v1"
        ));

        if (hasNextBus) {
            arrivals.add(new BusArrival(
                "20001",
                "5000",
                "BUS-002",
                NOW.plusMinutes(15),
                nextSeats,
                SeatType.OBSERVED,
                NOW,
                "v1"
            ));
        }

        return analyzer.analyze(new SegmentInput(
            1,
            "5000",
            "동백역",
            NOW,
            0,
            25,
            0,
            true,
            arrivals,
            NOW,
            true
        ));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nextSegment(
        SegmentView view
    ) throws Exception {

        StrategyView strategy = mock(StrategyView.class);
        when(strategy.analysis()).thenReturn(List.of(view));

        TripReevaluationService service =
            new TripReevaluationService(null, null, null);

        Method method = TripReevaluationService.class
            .getDeclaredMethod("nextSegment", StrategyView.class);

        method.setAccessible(true);

        return (Map<String, Object>) method.invoke(
            service,
            strategy
        );
    }
}
