package com.yongyong.transit.strategy.service;

import com.yongyong.transit.analysis.client.GbisClient.RouteStation;
import com.yongyong.transit.analysis.client.GbisClient.StationRoute;
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
import com.yongyong.transit.route.walking.service.WalkingRouteService.WalkingRouteResponse;
import com.yongyong.transit.strategy.service.StrategyService.Location;
import com.yongyong.transit.strategy.service.StrategyService.Mode;
import com.yongyong.transit.strategy.service.StrategyService.StrategyRequest;
import com.yongyong.transit.strategy.service.StrategyService.StrategyResponse;
import com.yongyong.transit.strategy.service.StrategyService.StrategyType;
import com.yongyong.transit.strategy.service.StrategyService.StrategyView;
import com.yongyong.transit.strategy.service.StrategyService.TripSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlternativeServiceTest {

    private static final OffsetDateTime NOW = at("07:30");

    private static final String DONGBAEK = "228000172";
    private static final String JUNEBU = "228000168";
    private static final String FAR_STOP = "228000160";
    private static final String OTHER_STOP = "228000300";
    private static final String GANGNAM = "121000009";

    private static final String ROUTE_5003A = "228000431";
    private static final String ROUTE_5005 = "228000175";
    private static final String ROUTE_1560 = "228000560";

    // 집 좌표. 동백쥬네브는 직선 약 667m(어림 13분), 먼 정류장은 직선 약 1.1km(직선만으로 16분)
    private static final Location HOME = new Location(127.1500, 37.2700);

    private BusRealtimeService realtime;
    private RouteStationService routeStations;
    private WalkingRouteService walking;
    private StrategyService service;
    private final Map<String, List<BusArrival>> arrivalsByStation = new HashMap<>();

    @BeforeEach
    void setUp() {
        realtime = mock(BusRealtimeService.class);
        routeStations = mock(RouteStationService.class);
        walking = mock(WalkingRouteService.class);
        when(realtime.loadCollectorSnapshot(any()))
            .thenReturn(CollectorSnapshot.empty());

        // 실제 조회처럼 요청한 노선만 돌려준다
        when(realtime.getArrivals(anyString(), any(), any(), any(), any()))
            .thenAnswer(invocation -> {
                String stationId = invocation.getArgument(0);
                Set<String> routeIds = invocation.getArgument(1);

                return new StationArrivals(
                    arrivalsByStation.getOrDefault(stationId, List.of())
                        .stream()
                        .filter(arrival -> routeIds.contains(arrival.routeId()))
                        .toList(),
                    at("07:29"),
                    false
                );
            });

        RouteAnalysisService analysis =
            new RouteAnalysisService(realtime, new SegmentAnalyzer());

        service = new StrategyService(
            analysis,
            realtime,
            new AlternativeService(analysis, routeStations, walking)
        );

        givenArrivals(DONGBAEK,
            arrival(ROUTE_5003A, "5003A", "A1", "07:41", 3),
            arrival(ROUTE_5003A, "5003A", "A2", "07:59", 3)
        );
    }

    @Test
    void 대체_노선은_정류장_수로_탑승_시간을_어림() {
        givenCommonRoutes(new StationRoute(ROUTE_5005, "5005"));
        when(routeStations.routeStations(eq(ROUTE_5005), any(), any()))
            .thenReturn(List.of(
                station(DONGBAEK, "동백2차", 10, null, null),
                station(GANGNAM, "강남역", 35, null, null)
            ));
        givenArrivals(DONGBAEK,
            arrival(ROUTE_5003A, "5003A", "A1", "07:41", 3),
            arrival(ROUTE_5005, "5005", "B1", "07:45", 10)
        );

        StrategyView alt = only(strategies(List.of(), null), StrategyType.ALT_ROUTE);

        assertThat(alt.title()).isEqualTo("5005번으로 바꾸기");
        assertThat(alt.extraWalkMin()).isZero();
        assertThat(alt.moveToName()).isNull();
        assertThat(alt.strategyRiskStage()).isEqualTo(Stage.OK);
        // 25정류장 × 2분 = 50분 > 원래 39분 → 07:45 탑승, 08:35 하차, 08:39 도착
        assertThat(alt.totalMinIfBoard()).isEqualTo(69);
        assertThat(alt.busArrivalAt()).isEqualTo(at("07:45"));

        TripSegment bus = alt.route().segments().get(1);
        assertThat(bus.gbisRouteId()).isEqualTo(ROUTE_5005);
        assertThat(bus.routeLabel()).isEqualTo("5005");
        assertThat(bus.plannedEndAt()).isEqualTo(at("08:35"));
    }

    @Test
    void 대체_노선이_다른_후보_경로에_있으면_그_경로_시간을_사용() {
        givenCommonRoutes(new StationRoute(ROUTE_5005, "5005"));
        givenArrivals(DONGBAEK,
            arrival(ROUTE_5003A, "5003A", "A1", "07:41", 3),
            arrival(ROUTE_5005, "5005", "B1", "07:45", 10)
        );

        RouteInput odsay5005 = route(1, 54,
            walk(0, 9),
            bus(1, 41, "동백2차", DONGBAEK, "강남역", GANGNAM, option("5005", ROUTE_5005)),
            walk(2, 4)
        );

        StrategyResponse response = strategies(List.of(odsay5005), null);
        StrategyView alt = only(response, StrategyType.ALT_ROUTE);

        // 07:45 + 41분 = 08:26 하차 → 08:30 도착
        assertThat(alt.totalMinIfBoard()).isEqualTo(60);
        verify(routeStations, never()).routeStations(eq(ROUTE_5005), any(), any());
        // 같은 정류장이라 우회로는 만들지 않는다
        assertThat(types(response)).doesNotContain(StrategyType.DETOUR);
    }

    @Test
    void 도착_시각을_모르거나_여유시간이_위험이면_후보에서_제외() {
        givenCommonRoutes(
            new StationRoute(ROUTE_5005, "5005"),
            new StationRoute(ROUTE_1560, "1560")
        );
        // 5005는 07:40 도착 → 정류장 도착 07:39, 여유 1분(RISK). 1560은 도착 정보 없음
        givenArrivals(DONGBAEK,
            arrival(ROUTE_5003A, "5003A", "A1", "07:41", 3),
            arrival(ROUTE_5005, "5005", "B1", "07:40", 10)
        );

        assertThat(types(strategies(List.of(), null)))
            .containsExactly(StrategyType.KEEP);
    }

    @Test
    void 거슬러타기는_카카오_도보_시간과_같은_차량의_시간_차를_사용() {
        givenRouteStations5003A();
        givenWalkSeconds(37.2760, 600);
        // A1은 쥬네브 07:37(이미 지나감), A2는 쥬네브 07:55 → 동백2차 07:59
        givenArrivals(JUNEBU,
            arrival(ROUTE_5003A, "5003A", "A1", "07:37", 8),
            arrival(ROUTE_5003A, "5003A", "A2", "07:55", 8)
        );

        StrategyView backtrack =
            only(strategies(List.of(), HOME), StrategyType.BACKTRACK);

        assertThat(backtrack.title()).isEqualTo("앞 정류장에서 5003A번 타기");
        assertThat(backtrack.moveToName()).isEqualTo("동백쥬네브");
        assertThat(backtrack.moveToX()).isEqualTo(127.1500);
        assertThat(backtrack.moveToY()).isEqualTo(37.2760);
        // 카카오 도보 10분, 원래 첫 도보 9분 → 추가 1분
        assertThat(backtrack.extraWalkMin()).isEqualTo(1);
        // 07:40 도착 → 07:55 A2 탑승 → 39 + 4(A1 07:37→07:41) = 43분 → 08:38 → 08:42
        assertThat(backtrack.busArrivalAt()).isEqualTo(at("07:55"));
        assertThat(backtrack.totalMinIfBoard()).isEqualTo(72);

        List<TripSegment> segments = backtrack.route().segments();
        assertThat(segments).extracting(TripSegment::mode)
            .containsExactly("WALK", "BUS", "WALK");
        assertThat(segments.get(0).alightName()).isEqualTo("동백쥬네브");
        assertThat(segments.get(0).walkMin()).isEqualTo(10);
        assertThat(segments.get(1).boardStationId()).isEqualTo(JUNEBU);
    }

    @Test
    void 직선거리로도_15분_넘는_정류장은_도보_API와_실시간_조회를_하지_않음() {
        givenRouteStations5003A();
        givenArrivals(JUNEBU, arrival(ROUTE_5003A, "5003A", "A2", "07:55", 8));

        strategies(List.of(), HOME);

        verify(walking, never()).search(
            anyDouble(), anyDouble(), anyDouble(), eq(37.2800), any()
        );
        verify(realtime, never()).getArrivals(eq(FAR_STOP), any(), any(), any(), any());
    }

    @Test
    void 도보_API가_실패하면_직선거리로_대신_계산() {
        givenRouteStations5003A();
        when(walking.search(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any()))
            .thenThrow(new IllegalStateException("kakao error"));
        givenArrivals(JUNEBU, arrival(ROUTE_5003A, "5003A", "A2", "07:55", 8));

        StrategyView backtrack =
            only(strategies(List.of(), HOME), StrategyType.BACKTRACK);

        // 667m × 1.3 ÷ 67 = 12.9 → 13분
        assertThat(backtrack.route().segments().get(0).walkMin()).isEqualTo(13);
    }

    @Test
    void 두_정류장에_같이_나오는_다른_차량으로도_이동_시간을_구함() {
        givenRouteStations5003A();
        givenArrivals(DONGBAEK,
            arrival(ROUTE_5003A, "5003A", "A1", "07:41", 3),
            arrival(ROUTE_5003A, "5003A", "A2", "08:00", 3)
        );
        // KEEP 차량 A1은 쥬네브 목록에 없고, A2만 두 정류장에 같이 나온다
        givenArrivals(JUNEBU, arrival(ROUTE_5003A, "5003A", "A2", "07:55", 8));

        StrategyView backtrack =
            only(strategies(List.of(), HOME), StrategyType.BACKTRACK);

        // 39 + 5(A2 07:55→08:00) = 44분 → 07:55 탑승 → 08:39 → 08:43
        assertThat(backtrack.totalMinIfBoard()).isEqualTo(73);
    }

    @Test
    void 앞_정류장이_예측_대상이_아니면_같은_차량의_원래_정류장_예측_좌석과_작은_값() {
        givenRouteStations5003A();
        givenArrivals(DONGBAEK,
            arrival(ROUTE_5003A, "5003A", "A1", "07:41", 3),
            predicted(ROUTE_5003A, "5003A", "A2", "07:59", 3)
        );
        givenArrivals(JUNEBU, arrival(ROUTE_5003A, "5003A", "A2", "07:55", 8));

        StrategyView backtrack =
            only(strategies(List.of(), HOME), StrategyType.BACKTRACK);

        assertThat(backtrack.remainSeats()).isEqualTo(3);
        assertThat(backtrack.seatType()).isEqualTo(SeatType.PREDICTED);
        assertThat(backtrack.strategyRiskStage()).isEqualTo(Stage.CAUTION);
    }

    @Test
    void 현재_위치가_없으면_거슬러타기를_만들지_않음() {
        givenRouteStations5003A();
        givenArrivals(JUNEBU, arrival(ROUTE_5003A, "5003A", "A2", "07:55", 8));

        assertThat(types(strategies(List.of(), null)))
            .doesNotContain(StrategyType.BACKTRACK);
        verify(routeStations, never()).routeStations(eq(ROUTE_5003A), any(), any());
    }

    @Test
    void 우회와_다른_교통수단은_다른_후보_경로를_그대로_사용() {
        when(routeStations.routeStations(eq(ROUTE_1560), any(), any()))
            .thenReturn(List.of(station(OTHER_STOP, "동백역", 3, 127.16, 37.27)));
        givenArrivals(OTHER_STOP, arrival(ROUTE_1560, "1560", "C1", "07:42", 10));

        RouteInput detour = route(1, 56,
            walk(0, 7),
            bus(1, 45, "동백역", OTHER_STOP, "강남역", GANGNAM, option("1560", ROUTE_1560)),
            walk(2, 4)
        );
        RouteInput subway = route(2, 65,
            walk(0, 12),
            subway(1, 50),
            walk(2, 3)
        );
        RouteInput farDetour = route(3, 70,
            walk(0, 20),
            bus(1, 40, "먼 정류장", FAR_STOP, "강남역", GANGNAM, option("1560", ROUTE_1560))
        );

        StrategyResponse response = strategies(List.of(detour, subway, farDetour), null);

        StrategyView detourView = only(response, StrategyType.DETOUR);
        assertThat(detourView.title()).isEqualTo("동백역에서 1560번 타기");
        assertThat(detourView.moveToName()).isEqualTo("동백역");
        assertThat(detourView.moveToX()).isEqualTo(127.16);
        assertThat(detourView.extraWalkMin()).isZero();
        // 07:37 정류장 → 07:42 탑승 → 08:27 → 08:31
        assertThat(detourView.totalMinIfBoard()).isEqualTo(61);

        StrategyView otherMode = only(response, StrategyType.OTHER_MODE);
        assertThat(otherMode.title()).isEqualTo("지하철로 가기");
        assertThat(otherMode.strategyRiskStage()).isEqualTo(Stage.OK);
        assertThat(otherMode.totalMinIfBoard()).isEqualTo(65);
        assertThat(otherMode.totalMinIfMiss()).isEqualTo(65);
        assertThat(otherMode.extraWalkMin()).isEqualTo(3);
        assertThat(otherMode.busArrivalAt()).isNull();

        verify(realtime, never()).getArrivals(eq(FAR_STOP), any(), any(), any(), any());
    }

    @Test
    void 버스에_타_있으면_다음_환승_버스의_대체_노선만_만듦() {
        givenRouteStations5003A();
        givenCommonRoutes(new StationRoute(ROUTE_5005, "5005"));
        givenArrivals(DONGBAEK,
            arrival(ROUTE_5003A, "5003A", "A2", "08:10", 3),
            arrival(ROUTE_5005, "5005", "B1", "08:12", 10)
        );

        StrategyResponse response = service.evaluate(
            route(0, 50,
                walk(2, 3),
                bus(3, 39, "동백2차", DONGBAEK, "강남역", GANGNAM, option("5003A", ROUTE_5003A))
            ),
            List.of(route(1, 60, walk(0, 12), subway(1, 50))),
            Map.of(),
            HOME,
            at("08:00"),
            Mode.REEVALUATION,
            NOW
        );

        assertThat(types(response))
            .containsExactly(StrategyType.KEEP, StrategyType.ALT_ROUTE);
        verify(routeStations, never()).routeStations(eq(ROUTE_5003A), any(), any());
    }

    @Test
    void 지하철만_타는_경로는_대안을_만들지_않음() {
        StrategyResponse response = service.strategies(new StrategyRequest(
            route(0, 60, walk(0, 5), subway(1, 50), walk(2, 5)),
            List.of(route(1, 70, walk(0, 5), subway(1, 60))),
            List.of(),
            HOME
        ), NOW);

        assertThat(types(response)).containsExactly(StrategyType.KEEP);
        verify(routeStations, never()).commonRoutes(any(), any(), any(), any(), any());
    }

    @Test
    void 호출_한도에_닿으면_dataDelayed() {
        when(realtime.getArrivals(anyString(), any(), any(), any(), any()))
            .thenAnswer(invocation -> {
                CallBudget budget = invocation.getArgument(4);

                while (budget.hasRemaining()) {
                    budget.use();
                }

                return new StationArrivals(
                    List.of(arrival(ROUTE_5003A, "5003A", "A1", "07:41", 3)),
                    at("07:29"),
                    false
                );
            });

        assertThat(strategies(List.of(), null).dataDelayed()).isTrue();
    }

    @Test
    void 도보_시간은_직선거리에_1_3배를_해서_분당_67m로_올림() {
        // 위도 0.006도 ≈ 667m → 667 × 1.3 ÷ 67 = 12.9 → 13분
        assertThat(AlternativeService.walkMin(HOME, 127.1500, 37.2760))
            .isEqualTo(13);
    }

    private StrategyResponse strategies(
        List<RouteInput> alternativeRoutes,
        Location location
    ) {
        return service.strategies(new StrategyRequest(
            route(0, 52,
                walk(0, 9),
                bus(1, 39, "동백2차", DONGBAEK, "강남역", GANGNAM,
                    option("5003A", ROUTE_5003A)),
                walk(2, 4)
            ),
            alternativeRoutes,
            List.of(),
            location
        ), NOW);
    }

    private static StrategyView only(StrategyResponse response, StrategyType type) {
        List<StrategyView> found = response.strategies()
            .stream()
            .filter(strategy -> strategy.strategyType() == type)
            .toList();

        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private static List<StrategyType> types(StrategyResponse response) {
        return response.strategies()
            .stream()
            .map(StrategyView::strategyType)
            .toList();
    }

    private void givenArrivals(String stationId, BusArrival... arrivals) {
        arrivalsByStation.put(stationId, List.of(arrivals));
    }

    private void givenWalkSeconds(double stationY, int seconds) {
        when(walking.search(anyDouble(), anyDouble(), anyDouble(), eq(stationY), any()))
            .thenReturn(new WalkingRouteResponse(
                "KAKAO", "SHORTEST", 700, seconds, List.of(), List.of()
            ));
    }

    private void givenCommonRoutes(StationRoute... routes) {
        when(routeStations.commonRoutes(eq(DONGBAEK), eq(GANGNAM), any(), any(), any()))
            .thenReturn(List.of(routes));
    }

    // 5003A: 먼 정류장(10) → 동백쥬네브(11) → 동백2차(12) → … → 강남역(30)
    private void givenRouteStations5003A() {
        List<RouteStation> stations = new ArrayList<>();
        stations.add(station(FAR_STOP, "먼 정류장", 10, 127.1500, 37.2800));
        stations.add(station(JUNEBU, "동백쥬네브", 11, 127.1500, 37.2760));
        stations.add(station(DONGBAEK, "동백2차", 12, 127.1526, 37.2691));
        stations.add(station(GANGNAM, "강남역", 30, 127.0276, 37.4979));

        when(routeStations.routeStations(eq(ROUTE_5003A), any(), any()))
            .thenReturn(stations);
    }

    private static RouteStation station(
        String id,
        String name,
        int seq,
        Double x,
        Double y
    ) {
        return new RouteStation(id, name, seq, x, y);
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
        String plateNo,
        String time,
        int seats
    ) {
        return new BusArrival(
            routeId, label, plateNo, at(time), seats,
            SeatType.OBSERVED, at("07:29"), null
        );
    }

    private static BusArrival predicted(
        String routeId,
        String label,
        String plateNo,
        String time,
        int seats
    ) {
        return new BusArrival(
            routeId, label, plateNo, at(time), seats,
            SeatType.PREDICTED, at("07:29"), "v-test"
        );
    }

    private static OffsetDateTime at(String hhmm) {
        return OffsetDateTime.parse("2026-11-04T" + hhmm + ":00+09:00");
    }
}
