package com.yongyong.transit.strategy.service;

import com.yongyong.transit.analysis.service.BusRealtimeService;
import com.yongyong.transit.analysis.service.BusRealtimeService.CollectorSnapshot;
import com.yongyong.transit.analysis.service.BusRealtimeService.StationArrivals;
import com.yongyong.transit.analysis.service.CallBudget;
import com.yongyong.transit.analysis.service.RouteAnalysisService;
import com.yongyong.transit.analysis.service.RouteAnalysisService.BusOption;
import com.yongyong.transit.analysis.service.RouteAnalysisService.RouteInput;
import com.yongyong.transit.analysis.service.RouteAnalysisService.RouteSegment;
import com.yongyong.transit.analysis.service.SegmentAnalyzer;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.BusArrival;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SeatType;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.Stage;
import com.yongyong.transit.route.walking.service.WalkingRouteService;
import com.yongyong.transit.strategy.service.StrategyService.AlightUpdate;
import com.yongyong.transit.strategy.service.StrategyService.Mode;
import com.yongyong.transit.strategy.service.StrategyService.StrategyRequest;
import com.yongyong.transit.strategy.service.StrategyService.StrategyResponse;
import com.yongyong.transit.strategy.service.StrategyService.StrategyType;
import com.yongyong.transit.strategy.service.StrategyService.StrategyView;
import com.yongyong.transit.strategy.service.StrategyService.TripSegment;
import com.yongyong.transit.common.client.OpenAiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StrategyServiceTest {

    private static final OffsetDateTime NOW = at("07:30");

    private static final String DONGBAEK = "228000172";
    private static final String TRANSFER_STOP = "228000500";
    private static final String GANGNAM = "121000009";

    private static final String ROUTE_5005 = "228000175";
    private static final String ROUTE_5003A = "228000431";
    private static final String ROUTE_10 = "228000010";

    private BusRealtimeService realtime;
    private StrategyService service;

    @BeforeEach
    void setUp() {
        realtime = mock(BusRealtimeService.class);
        when(realtime.loadCollectorSnapshot(any()))
            .thenReturn(CollectorSnapshot.empty());

        RouteAnalysisService analysis =
            new RouteAnalysisService(realtime, new SegmentAnalyzer());

        service = new StrategyService(
            analysis,
            realtime,
            new AlternativeService(
                analysis,
                mock(RouteStationService.class),
                mock(WalkingRouteService.class)
            ),
            new ReasonService(new OpenAiClient("", "", ""))
        );
    }

    @Test
    void 버스1개_경로의_KEEP_전략() {
        givenStation(DONGBAEK,
            arrival(ROUTE_5005, "5005", "07:41", 3),
            arrival(ROUTE_5003A, "5003A", "07:59", 18)
        );

        StrategyResponse response = strategies(route(0, 58,
            walk(0, 9),
            bus(1, 39, "동백2차", DONGBAEK, "강남역", GANGNAM,
                option("5005", ROUTE_5005), option("5003A", ROUTE_5003A)),
            walk(2, 4)
        ));

        assertThat(response.dataBasisAt()).isEqualTo(at("07:29"));
        assertThat(response.currentRoute().strategyRiskStage())
            .isEqualTo(Stage.CAUTION);
        assertThat(response.strategies()).hasSize(1);

        StrategyView keep = response.strategies().get(0);
        assertThat(keep.strategyType()).isEqualTo(StrategyType.KEEP);
        assertThat(keep.isCurrent()).isTrue();
        assertThat(keep.title()).isEqualTo("그대로 5005번 타기");
        assertThat(keep.strategyRiskStage()).isEqualTo(Stage.CAUTION);
        assertThat(keep.extraWalkMin()).isZero();
        // 07:39 정류장 도착 → 07:41 탑승 → 08:20 하차 → 08:24 도착
        assertThat(keep.totalMinIfBoard()).isEqualTo(54);
        assertThat(keep.expectedArrivalAt()).isEqualTo(at("08:24"));
        // 놓치면 07:59 → 08:38 → 08:42
        assertThat(keep.totalMinIfMiss()).isEqualTo(72);
        // 주의 단계라 놓쳤을 때 시간이 비교값, 대안이 없으니 추천
        assertThat(keep.comparisonValueMin()).isEqualTo(72);
        assertThat(keep.recommended()).isTrue();
        assertThat(keep.busArrivalAt()).isEqualTo(at("07:41"));
        assertThat(keep.nextBusArrivalAt()).isEqualTo(at("07:59"));
        assertThat(keep.remainSeats()).isEqualTo(3);
        assertThat(keep.seatType()).isEqualTo(SeatType.PREDICTED);
        assertThat(keep.missIntervalMin()).isNull();

        List<TripSegment> segments = keep.route().segments();
        assertThat(segments).hasSize(3);
        assertThat(segments.get(0)).isEqualTo(new TripSegment(
            0, 1, "WALK", null, null, null, null, "출발지", "동백2차", "NA",
            at("07:30"), at("07:39"), 9, null
        ));
        assertThat(segments.get(1)).isEqualTo(new TripSegment(
            1, 2, "BUS", ROUTE_5005, "5005", DONGBAEK, GANGNAM, "동백2차", "강남역",
            "MATCHED", at("07:41"), at("08:20"), null, null
        ));
        assertThat(segments.get(2)).isEqualTo(new TripSegment(
            2, 3, "WALK", null, null, null, null, "강남역", "도착지", "NA",
            at("08:20"), at("08:24"), 4, null
        ));

        assertThat(keep.analysis()).hasSize(1);
        assertThat(keep.analysis().get(0).segmentIndex()).isEqualTo(1);
        assertThat(keep.analysis().get(0).routeLabel()).isEqualTo("5005");
    }

    @Test
    void 버스2번_경로는_환승_대기와_연쇄_지연을_포함() {
        givenStation(DONGBAEK,
            arrival(ROUTE_5005, "5005", "07:40", 10),
            arrival(ROUTE_5005, "5005", "07:50", 10)
        );
        givenStation(TRANSFER_STOP,
            arrival(ROUTE_10, "10", "08:05", 10),
            arrival(ROUTE_10, "10", "08:20", 10)
        );

        StrategyView keep = keep(route(0, 40,
            walk(0, 5),
            bus(1, 20, "동백2차", DONGBAEK, "환승", null, option("5005", ROUTE_5005)),
            walk(2, 3),
            bus(3, 10, "환승", TRANSFER_STOP, "강남역", null, option("10", ROUTE_10)),
            walk(4, 2)
        ));

        // 07:40 탑승 → 08:00 하차 → 08:03 환승 정류장 → 08:05 탑승 → 08:15 → 08:17
        assertThat(keep.totalMinIfBoard()).isEqualTo(47);
        // 07:50 탑승 → 08:10 → 08:13 → 08:05 버스는 놓쳐 08:20 탑승 → 08:30 → 08:32
        assertThat(keep.totalMinIfMiss()).isEqualTo(62);

        TripSegment second = keep.route().segments().get(3);
        assertThat(second.plannedStartAt()).isEqualTo(at("08:05"));
        assertThat(second.plannedEndAt()).isEqualTo(at("08:15"));
        assertThat(keep.analysis()).extracting("segmentIndex").containsExactly(1, 3);
    }

    @Test
    void 다음_버스가_없으면_놓쳤을_때_소요는_null() {
        givenStation(DONGBAEK, arrival(ROUTE_5005, "5005", "07:41", 10));

        StrategyView keep = keep(route(0, 50,
            walk(0, 9),
            bus(1, 39, "동백2차", DONGBAEK, "강남역", null, option("5005", ROUTE_5005))
        ));

        assertThat(keep.totalMinIfBoard()).isEqualTo(50);
        assertThat(keep.totalMinIfMiss()).isNull();
        assertThat(keep.nextBusArrivalAt()).isNull();
        assertThat(keep.comparisonValueMin()).isEqualTo(50);
    }

    @Test
    void 도착_정보가_없으면_경로_시간으로_이어서_계산하고_주의() {
        givenStation(DONGBAEK);

        StrategyView keep = keep(route(0, 52,
            walk(0, 9),
            bus(1, 39, "동백2차", DONGBAEK, "강남역", null, option("5005", ROUTE_5005)),
            walk(2, 4)
        ));

        assertThat(keep.strategyRiskStage()).isEqualTo(Stage.CAUTION);
        assertThat(keep.totalMinIfBoard()).isEqualTo(52);
        assertThat(keep.title()).isEqualTo("그대로 5005번 타기");
        assertThat(keep.route().segments().get(1).plannedStartAt())
            .isEqualTo(at("07:39"));
        assertThat(keep.route().segments().get(1).gbisRouteId())
            .isEqualTo(ROUTE_5005);
    }

    @Test
    void 지하철_하차_시각을_다음_버스_기준으로_사용() {
        givenStation(TRANSFER_STOP,
            arrival(ROUTE_10, "10", "08:05", 10),
            arrival(ROUTE_10, "10", "08:20", 10)
        );

        RouteInput route = route(0, 50,
            walk(0, 5),
            subway(1, 30),
            walk(2, 2),
            bus(3, 10, "환승", TRANSFER_STOP, "강남역", null, option("10", ROUTE_10))
        );

        // 하차 시각이 없으면 08:07 정류장 도착 → 08:20 탑승
        assertThat(keep(route).busArrivalAt()).isEqualTo(at("08:20"));

        StrategyResponse response = service.strategies(new StrategyRequest(
            route, List.of(),
            List.of(new AlightUpdate(1, at("07:58"))),
            null
        ), NOW);

        StrategyView keep = response.strategies().get(0);
        // 07:58 하차 → 08:00 정류장 → 08:05 탑승 → 08:15 도착
        assertThat(keep.busArrivalAt()).isEqualTo(at("08:05"));
        assertThat(keep.totalMinIfBoard()).isEqualTo(45);
        assertThat(response.currentRoute().segments().get(0).predictedBusArrivalAt())
            .isEqualTo(at("08:05"));

        TripSegment subway = keep.route().segments().get(1);
        assertThat(subway.plannedEndAt()).isEqualTo(at("07:58"));
        assertThat(subway.userAlightAt()).isEqualTo(at("07:58"));
        assertThat(subway.walkMin()).isNull();
    }

    @Test
    void 지하철만_타는_경로는_KEEP만_보내고_단계_OK() {
        StrategyResponse response = strategies(route(1, 64,
            walk(0, 5),
            subway(1, 50),
            walk(2, 9)
        ));

        assertThat(response.dataBasisAt()).isNull();
        assertThat(response.currentRoute().analyzable()).isTrue();
        assertThat(response.currentRoute().strategyRiskStage()).isNull();
        assertThat(response.currentRoute().segments()).isEmpty();

        StrategyView keep = response.strategies().get(0);
        assertThat(response.strategies()).hasSize(1);
        assertThat(keep.strategyRiskStage()).isEqualTo(Stage.OK);
        assertThat(keep.totalMinIfBoard()).isEqualTo(64);
        assertThat(keep.totalMinIfMiss()).isEqualTo(64);
        assertThat(keep.comparisonValueMin()).isEqualTo(64);
        assertThat(keep.busArrivalAt()).isNull();
        assertThat(keep.analysis()).isEmpty();
        assertThat(keep.route().segments()).hasSize(3);
        assertThat(keep.route().segments().get(2).plannedEndAt())
            .isEqualTo(at("08:34"));
    }

    @Test
    void 재평가는_기준_시각부터_계산하고_요청당_한도_안에서_실시간_조회() {
        givenStation(TRANSFER_STOP, arrival(ROUTE_10, "10", "08:05", 10));

        StrategyResponse response = service.evaluate(
            route(0, 20,
                walk(2, 3),
                bus(3, 10, "환승", TRANSFER_STOP, "강남역", null, option("10", ROUTE_10))
            ),
            List.of(),
            Map.of(),
            null,
            at("08:00"),
            Mode.REEVALUATION,
            NOW
        );

        StrategyView keep = response.strategies().get(0);
        assertThat(keep.route().segments().get(0).plannedStartAt())
            .isEqualTo(at("08:00"));
        assertThat(keep.busArrivalAt()).isEqualTo(at("08:05"));

        ArgumentCaptor<CallBudget> budget = ArgumentCaptor.forClass(CallBudget.class);
        verify(realtime).getArrivals(
            eq(TRANSFER_STOP), any(), any(), any(), budget.capture()
        );
        assertThat(budget.getValue().hasRemaining()).isTrue();
    }

    @Test
    void 고른_경로가_없거나_형식이_틀리면_400() {
        assertThatThrownBy(() -> service.strategies(null, NOW))
            .hasMessageContaining("선택한 경로 정보가 올바르지 않습니다.");

        assertThatThrownBy(() -> service.strategies(
            new StrategyRequest(null, List.of(), List.of(), null), NOW
        )).hasMessageContaining("선택한 경로 정보가 올바르지 않습니다.");

        List<RouteSegment> withNull = new ArrayList<>();
        withNull.add(null);

        assertThatThrownBy(() -> service.strategies(
            new StrategyRequest(new RouteInput(0, 10, withNull), null, null, null),
            NOW
        )).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void 버스_구간이_모두_FAILED면_400() {
        assertThatThrownBy(() -> strategies(route(0, 50,
            walk(0, 5),
            bus(1, 40, "강남역", GANGNAM, "양재", null, option("140", "100100022"))
        ))).hasMessageContaining("분석할 수 없는 경로입니다.");
    }

    @Test
    void 하차_시각이_지하철_구간이_아니거나_지금보다_이전이면_400() {
        RouteInput route = route(0, 50, walk(0, 5), subway(1, 45));

        assertThatThrownBy(() -> service.strategies(new StrategyRequest(
            route, List.of(), List.of(new AlightUpdate(0, at("07:50"))), null
        ), NOW)).hasMessageContaining("하차 시각이 올바르지 않습니다.");

        assertThatThrownBy(() -> service.strategies(new StrategyRequest(
            route, List.of(), List.of(new AlightUpdate(1, at("07:20"))), null
        ), NOW)).hasMessageContaining("하차 시각이 올바르지 않습니다.");

        assertThatThrownBy(() -> service.strategies(new StrategyRequest(
            route, List.of(), List.of(new AlightUpdate(1, null)), null
        ), NOW)).hasMessageContaining("하차 시각이 올바르지 않습니다.");
    }

    private StrategyResponse strategies(RouteInput route) {
        return service.strategies(
            new StrategyRequest(route, List.of(), List.of(), null),
            NOW
        );
    }

    private StrategyView keep(RouteInput route) {
        return strategies(route).strategies().get(0);
    }

    private void givenStation(String stationId, BusArrival... arrivals) {
        when(realtime.getArrivals(eq(stationId), any(), any(), any(), any()))
            .thenReturn(new StationArrivals(
                new ArrayList<>(List.of(arrivals)),
                at("07:29"),
                false
            ));
    }

    private static RouteInput route(
        int routeIndex,
        int totalTimeMin,
        RouteSegment... segments
    ) {
        return new RouteInput(routeIndex, totalTimeMin, List.of(segments));
    }

    private static RouteSegment walk(int index, int minutes) {
        return new RouteSegment(
            index, "WALK", minutes, null, null, null, null, "NA", List.of()
        );
    }

    private static RouteSegment subway(int index, int minutes) {
        return new RouteSegment(
            index, "SUBWAY", minutes, "동백", "강남", null, null, "NA", null
        );
    }

    private static RouteSegment bus(
        int index,
        int minutes,
        String startName,
        String startStationId,
        String endName,
        String endStationId,
        BusOption... options
    ) {
        return new RouteSegment(
            index, "BUS", minutes, startName, endName,
            startStationId, endStationId, null, List.of(options)
        );
    }

    private static BusOption option(String busNo, String routeId) {
        return new BusOption(busNo, routeId);
    }

    private static BusArrival arrival(
        String routeId,
        String label,
        String time,
        int seats
    ) {
        return new BusArrival(
            routeId, label, "plate-" + time, at(time), seats,
            SeatType.PREDICTED, at("07:29"), null
        );
    }

    private static OffsetDateTime at(String hhmm) {
        return OffsetDateTime.parse("2026-11-04T" + hhmm + ":00+09:00");
    }
}
