package com.yongyong.transit.analysis.service;

import com.yongyong.transit.analysis.service.BusRealtimeService.CollectorSnapshot;
import com.yongyong.transit.analysis.service.BusRealtimeService.StationArrivals;
import com.yongyong.transit.analysis.service.RouteAnalysisService.AnalysisRequest;
import com.yongyong.transit.analysis.service.RouteAnalysisService.AnalysisResponse;
import com.yongyong.transit.analysis.service.RouteAnalysisService.BusOption;
import com.yongyong.transit.analysis.service.RouteAnalysisService.RouteAnalysis;
import com.yongyong.transit.analysis.service.RouteAnalysisService.RouteInput;
import com.yongyong.transit.analysis.service.RouteAnalysisService.RoutePlan;
import com.yongyong.transit.analysis.service.RouteAnalysisService.RouteSegment;
import com.yongyong.transit.analysis.service.RouteAnalysisService.SegmentView;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.BusArrival;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SeatType;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouteAnalysisServiceTest {

    private static final OffsetDateTime NOW = at("07:30");

    private static final String DONGBAEK = "228000172";
    private static final String GANGNAM_BUS_STOP = "228000999";
    private static final String SEOUL_STOP = "121000009";

    private static final String ROUTE_5005 = "228000175";
    private static final String ROUTE_5003A = "228000431";
    private static final String ROUTE_1500 = "228000500";

    private BusRealtimeService realtime;
    private RouteAnalysisService service;

    @BeforeEach
    void setUp() {
        realtime = mock(BusRealtimeService.class);
        when(realtime.loadCollectorSnapshot(any()))
            .thenReturn(CollectorSnapshot.empty());

        service = new RouteAnalysisService(realtime, new SegmentAnalyzer());
    }

    @Test
    void 명세_예시_버스1개_경로() {
        givenStation(DONGBAEK, false,
            arrival(ROUTE_5005, "5005", "07:41", 3),
            arrival(ROUTE_5003A, "5003A", "07:59", 18)
        );

        AnalysisResponse response = analyze(route(0, 58,
            walk(0, 9),
            bus(1, 39, "동백2차", DONGBAEK,
                option("5005", ROUTE_5005), option("5003A", ROUTE_5003A)),
            walk(2, 4)
        ));

        RouteAnalysis route = response.routes().get(0);
        assertThat(route.analyzable()).isTrue();
        assertThat(route.strategyRiskStage()).isEqualTo(Stage.CAUTION);
        assertThat(route.segments()).hasSize(1);

        SegmentView segment = route.segments().get(0);
        assertThat(segment.segmentIndex()).isEqualTo(1);
        assertThat(segment.routeLabel()).isEqualTo("5005");
        assertThat(segment.slackMin()).isEqualTo(2);
        assertThat(segment.arriveIfBoard()).isEqualTo(at("08:24"));
        assertThat(segment.totalMinIfBoard()).isEqualTo(54);
        assertThat(segment.nextRouteLabel()).isEqualTo("5003A");
        assertThat(segment.totalMinIfMiss()).isEqualTo(72);

        assertThat(response.evaluatedAt()).isEqualTo(NOW);
        assertThat(response.dataBasisAt()).isEqualTo(at("07:29"));
        assertThat(response.dataDelayed()).isFalse();
    }

    @Test
    void 버스2개_경로는_앞버스_하차시각을_다음버스_기준으로() {
        givenStation(DONGBAEK, false, arrival(ROUTE_5005, "5005", "07:41", 10));
        // 첫 버스 하차 08:20 + 도보 5분 = 08:25 정류장 도착
        givenStation(GANGNAM_BUS_STOP, false,
            arrival(ROUTE_1500, "1500", "08:28", 10));

        AnalysisResponse response = analyze(route(0, 80,
            walk(0, 9),
            bus(1, 39, "동백2차", DONGBAEK, option("5005", ROUTE_5005)),
            walk(2, 5),
            bus(3, 20, "강남", GANGNAM_BUS_STOP, option("1500", ROUTE_1500)),
            walk(4, 3)
        ));

        List<SegmentView> segments = response.routes().get(0).segments();
        assertThat(segments).hasSize(2);

        // 앞 버스: 하차 시각, 총 소요는 목적지까지 (08:20 + 5 + 20 + 3 = 08:48)
        assertThat(segments.get(0).arriveIfBoard()).isEqualTo(at("08:20"));
        assertThat(segments.get(0).totalMinIfBoard()).isEqualTo(78);

        // 뒤 버스: 08:25 정류장 도착, 08:28 버스 → 여유 3분
        assertThat(segments.get(1).slackMin()).isEqualTo(3);
        assertThat(segments.get(1).arriveIfBoard()).isEqualTo(at("08:51"));
    }

    @Test
    void 앞버스_도착정보가_없으면_ODsay_소요시간으로_이어서_계산() {
        givenStation(DONGBAEK, false);
        // 07:30 + 도보 9 + 버스 39 + 도보 5 = 08:23 정류장 도착
        givenStation(GANGNAM_BUS_STOP, false,
            arrival(ROUTE_1500, "1500", "08:30", 10));

        AnalysisResponse response = analyze(route(0, 80,
            walk(0, 9),
            bus(1, 39, "동백2차", DONGBAEK, option("5005", ROUTE_5005)),
            walk(2, 5),
            bus(3, 20, "강남", GANGNAM_BUS_STOP, option("1500", ROUTE_1500))
        ));

        List<SegmentView> segments = response.routes().get(0).segments();
        assertThat(segments.get(0).boardingStage()).isEqualTo(Stage.CAUTION);
        assertThat(segments.get(0).predictedBusArrivalAt()).isNull();
        assertThat(segments.get(1).slackMin()).isEqualTo(7);
    }

    @Test
    void 일부만_FAILED면_FAILED구간은_null로_넣고_단계계산에서_제외() {
        givenStation(DONGBAEK, false, arrival(ROUTE_5005, "5005", "07:50", 10));

        AnalysisResponse response = analyze(route(0, 80,
            walk(0, 9),
            bus(1, 39, "동백2차", DONGBAEK, option("5005", ROUTE_5005)),
            bus(2, 15, "강남역", SEOUL_STOP, option("146", "100100024"))
        ));

        RouteAnalysis route = response.routes().get(0);
        assertThat(route.analyzable()).isTrue();
        assertThat(route.strategyRiskStage()).isEqualTo(Stage.OK);
        assertThat(route.segments()).hasSize(2);

        SegmentView failed = route.segments().get(1);
        assertThat(failed.segmentIndex()).isEqualTo(2);
        assertThat(failed.routeLabel()).isEqualTo("146");
        assertThat(failed.boardName()).isEqualTo("강남역");
        assertThat(failed.boardingStage()).isNull();
        assertThat(failed.predictedBusArrivalAt()).isNull();
        verify(realtime, never()).getArrivals(eq(SEOUL_STOP), any(), any(), any(), any());
    }

    @Test
    void 버스구간이_모두_FAILED면_분석불가() {
        AnalysisResponse response = analyze(route(2, 72,
            walk(0, 5),
            bus(1, 30, "강남역", SEOUL_STOP, option("146", "100100024"))
        ));

        RouteAnalysis route = response.routes().get(0);
        assertThat(route.analyzable()).isFalse();
        assertThat(route.strategyRiskStage()).isNull();
        assertThat(route.totalTimeMin()).isEqualTo(72);
        assertThat(route.segments()).isEmpty();
        assertThat(response.dataBasisAt()).isNull();
        verify(realtime, never()).loadCollectorSnapshot(any());
    }

    @Test
    void 버스가_없는_경로는_단계_null() {
        AnalysisResponse response = analyze(route(1, 64,
            walk(0, 5),
            new RouteSegment(1, "SUBWAY", 50, "동백", null, null, null, null, null),
            walk(2, 9)
        ));

        RouteAnalysis route = response.routes().get(0);
        assertThat(route.analyzable()).isTrue();
        assertThat(route.strategyRiskStage()).isNull();
        assertThat(route.segments()).isEmpty();
    }

    @Test
    void 요청에_matchStatus가_있으면_그대로_사용() {
        RouteSegment givenFailed = new RouteSegment(
            1, "BUS", 39, "동백2차", null, DONGBAEK, null, "FAILED",
            List.of(option("5005", ROUTE_5005))
        );

        AnalysisResponse response = analyze(route(0, 58, givenFailed));

        assertThat(response.routes().get(0).analyzable()).isFalse();
        verify(realtime, never()).getArrivals(anyString(), any(), any(), any(), any());
    }

    @Test
    void 요청이_MATCHED여도_정류장ID가_없으면_FAILED로_처리() {
        RouteSegment noStation = new RouteSegment(
            1, "BUS", 39, "동백2차", null, null, null, "MATCHED",
            List.of(option("5005", ROUTE_5005))
        );

        AnalysisResponse response = analyze(route(0, 58, noStation));

        assertThat(response.routes().get(0).analyzable()).isFalse();
        verify(realtime, never()).getArrivals(any(), any(), any(), any(), any());
    }

    @Test
    void 같은_정류장은_여러_경로에서_한번만_조회() {
        givenStation(DONGBAEK, false,
            arrival(ROUTE_5005, "5005", "07:45", 10),
            arrival(ROUTE_5003A, "5003A", "07:50", 10)
        );

        AnalysisResponse response = analyze(
            route(0, 58, bus(0, 39, "동백2차", DONGBAEK, option("5005", ROUTE_5005))),
            route(1, 60, bus(0, 41, "동백2차", DONGBAEK, option("5003A", ROUTE_5003A)))
        );

        verify(realtime, times(1))
            .getArrivals(eq(DONGBAEK), eq(Set.of(ROUTE_5005, ROUTE_5003A)), any(), any(), any());
        assertThat(response.routes().get(0).segments().get(0).routeLabel()).isEqualTo("5005");
        assertThat(response.routes().get(1).segments().get(0).routeLabel()).isEqualTo("5003A");
    }

    @Test
    void 캐시로_대체했거나_4분_넘게_지난_데이터면_dataDelayed() {
        givenStation(DONGBAEK, true, arrival(ROUTE_5005, "5005", "07:45", 10));

        assertThat(analyze(route(0, 58,
            bus(0, 39, "동백2차", DONGBAEK, option("5005", ROUTE_5005))
        )).dataDelayed()).isTrue();

        when(realtime.getArrivals(eq(DONGBAEK), any(), any(), any(), any()))
            .thenReturn(new StationArrivals(List.of(), at("07:25"), false));

        assertThat(analyze(route(0, 58,
            bus(0, 39, "동백2차", DONGBAEK, option("5005", ROUTE_5005))
        )).dataDelayed()).isTrue();
    }

    @Test
    void 지하철끼리_갈아타면_빠진_시간을_다음_지하철_타기_전에_기다림() {
        // 구간 합 51분, ODsay 총 59분 → 갈아타는 두 곳에 4분씩
        RouteInput route = route(0, 59,
            walk(0, 1), subway(1, 6), walk(2, 5), subway(3, 18),
            walk(4, 3), subway(5, 15), walk(6, 3)
        );

        RoutePlan plan = service.plan(route, Map.of(), Map.of(), false, NOW, NOW);

        assertThat(plan.arrivalAt()).isEqualTo(at("08:29"));
        // 도보는 07:42에 끝나고 지하철은 4분 기다린 07:46에 탐 (도보·탑승 시간은 그대로)
        assertThat(plan.segments().get(2).plannedEndAt()).isEqualTo(at("07:42"));
        assertThat(plan.segments().get(3).plannedStartAt()).isEqualTo(at("07:46"));
        assertThat(plan.segments().get(3).plannedEndAt()).isEqualTo(at("08:04"));
    }

    @Test
    void 환승_대기는_똑같이_나누고_나머지는_앞쪽부터() {
        RouteInput route = route(0, 60,
            walk(0, 1), subway(1, 6), walk(2, 5), subway(3, 18),
            walk(4, 3), subway(5, 15), walk(6, 3)
        );

        assertThat(service.transferWaits(route)).isEqualTo(Map.of(3, 5, 5, 4));
    }

    @Test
    void 지하철끼리_갈아타는_곳이_없거나_총_시간이_없으면_대기를_넣지_않음() {
        // 지하철 → 버스: 차이가 있어도 지하철끼리 갈아타지 않음
        assertThat(service.transferWaits(route(0, 70,
            walk(0, 1), subway(1, 10), walk(2, 2),
            bus(3, 39, "동백2차", DONGBAEK, option("5005", ROUTE_5005))
        ))).isEmpty();
        // 구간 합과 총 시간이 같음
        assertThat(service.transferWaits(route(0, 26,
            walk(0, 1), subway(1, 6), walk(2, 5), subway(3, 14)
        ))).isEmpty();
        // 재평가처럼 총 시간이 없음
        assertThat(service.transferWaits(new RouteInput(0, null, List.of(
            walk(0, 1), subway(1, 6), walk(2, 5), subway(3, 14)
        )))).isEmpty();
    }

    @Test
    void 지하철_환승_뒤_버스는_대기만큼_늦게_정류장에_도착() {
        // 07:30 + 도보 1 + 지하철 6 + 도보 5 + 대기 5 + 지하철 3 + 도보 1 = 07:51 정류장 도착
        givenStation(DONGBAEK, false, arrival(ROUTE_5005, "5005", "07:54", 10));

        AnalysisResponse response = analyze(route(0, 74,
            walk(0, 1), subway(1, 6), walk(2, 5), subway(3, 3), walk(4, 1),
            bus(5, 49, "상갈역", DONGBAEK, option("5005", ROUTE_5005)),
            walk(6, 4)
        ));

        SegmentView segment = response.routes().get(0).segments().get(0);
        assertThat(segment.slackMin()).isEqualTo(3);
        assertThat(segment.totalMinIfBoard()).isEqualTo(77);
    }

    @Test
    void 버스로_시작하고_기준이_지금이면_정류장에_있으므로_곧_오는_버스도_여유OK() {
        // 1분 뒤 버스: 걸어가야 하면 RISK지만 이미 정류장에 있으면 탈 수 있음
        Map<String, StationArrivals> arrivals = Map.of(DONGBAEK, new StationArrivals(
            List.of(arrival(ROUTE_5005, "5005", "07:31", 10)), at("07:29"), false
        ));

        RoutePlan atStop = service.plan(route(0, 40,
            bus(0, 39, "동백2차", DONGBAEK, option("5005", ROUTE_5005))
        ), arrivals, Map.of(), false, NOW, NOW);

        RoutePlan walking = service.plan(route(0, 41,
            walk(0, 0),
            bus(1, 39, "동백2차", DONGBAEK, option("5005", ROUTE_5005))
        ), arrivals, Map.of(), false, NOW, NOW);

        assertThat(atStop.firstBus().slackMin()).isEqualTo(1);
        assertThat(atStop.firstBus().slackStage()).isEqualTo(Stage.OK);
        assertThat(walking.firstBus().slackStage()).isEqualTo(Stage.RISK);
    }

    @Test
    void 기준_시각이_나중이면_버스로_시작해도_정류장에_있는_것으로_보지_않음() {
        // 앞 버스에서 07:40에 내려 같은 정류장에서 갈아타는 경우
        Map<String, StationArrivals> arrivals = Map.of(DONGBAEK, new StationArrivals(
            List.of(arrival(ROUTE_5005, "5005", "07:41", 10)), at("07:29"), false
        ));

        RoutePlan plan = service.plan(route(0, 40,
            bus(0, 39, "동백2차", DONGBAEK, option("5005", ROUTE_5005))
        ), arrivals, Map.of(), false, at("07:40"), NOW);

        assertThat(plan.firstBus().slackStage()).isEqualTo(Stage.RISK);
    }

    @Test
    void routes가_없으면_400() {
        assertThatThrownBy(() -> service.analyze(new AnalysisRequest(null), NOW))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.analyze(new AnalysisRequest(List.of()), NOW))
            .isInstanceOf(ResponseStatusException.class);

        List<RouteSegment> withNull = new ArrayList<>();
        withNull.add(null);

        assertThatThrownBy(() -> service.analyze(
            new AnalysisRequest(List.of(new RouteInput(0, 10, withNull))), NOW
        )).isInstanceOf(ResponseStatusException.class);
    }

    private AnalysisResponse analyze(RouteInput... routes) {
        return service.analyze(new AnalysisRequest(Arrays.asList(routes)), NOW);
    }

    private void givenStation(
        String stationId,
        boolean delayed,
        BusArrival... arrivals
    ) {
        when(realtime.getArrivals(eq(stationId), any(), any(), any(), any()))
            .thenReturn(new StationArrivals(
                new ArrayList<>(List.of(arrivals)),
                at("07:29"),
                delayed
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
            index, "WALK", minutes, null, null, null, null, null, List.of()
        );
    }

    private static RouteSegment subway(int index, int minutes) {
        return new RouteSegment(
            index, "SUBWAY", minutes, null, null, null, null, null, List.of()
        );
    }

    private static RouteSegment bus(
        int index,
        int minutes,
        String startName,
        String stationId,
        BusOption... options
    ) {
        return new RouteSegment(
            index, "BUS", minutes, startName, null, stationId, null, null,
            List.of(options)
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
            routeId, label, "plate-" + label, at(time), seats,
            SeatType.PREDICTED, at("07:29"), null
        );
    }

    private static OffsetDateTime at(String hhmm) {
        return OffsetDateTime.parse("2026-11-04T" + hhmm + ":00+09:00");
    }
}
