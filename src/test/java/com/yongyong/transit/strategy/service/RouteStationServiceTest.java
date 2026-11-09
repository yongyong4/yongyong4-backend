package com.yongyong.transit.strategy.service;

import com.yongyong.transit.analysis.client.GbisClient;
import com.yongyong.transit.analysis.client.GbisClient.AroundStation;
import com.yongyong.transit.analysis.client.GbisClient.RouteStation;
import com.yongyong.transit.analysis.client.GbisClient.StationRoute;
import com.yongyong.transit.analysis.service.CallBudget;
import com.yongyong.transit.analysis.service.GbisCallLimiter;
import com.yongyong.transit.strategy.service.RouteStationService.CommonRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouteStationServiceTest {

    private static final String ROUTE_5003A = "228000431";
    private static final String DONGBAEK = "228000172";
    private static final String GANGNAM = "121000009";
    private static final String GANGNAM_B = "121000010";
    private static final OffsetDateTime NOW =
        OffsetDateTime.parse("2026-11-04T07:30:00+09:00");

    private static final List<RouteStation> STATIONS = List.of(
        new RouteStation("228000168", "동백쥬네브", 11, 127.1501, 37.2712),
        new RouteStation(DONGBAEK, "동백2차", 12, 127.1526, 37.2691)
    );

    private GbisClient gbisClient;
    private RouteStationService service;

    @BeforeEach
    void setUp() {
        gbisClient = mock(GbisClient.class);
        when(gbisClient.hasKey()).thenReturn(true);
        service = new RouteStationService(gbisClient, new GbisCallLimiter(1000));
    }

    @Test
    void 같은_날에는_캐시를_쓰고_GBIS는_한번만_부름() {
        when(gbisClient.getRouteStations(ROUTE_5003A)).thenReturn(STATIONS);

        service.routeStations(ROUTE_5003A, CallBudget.of(20), NOW);
        List<RouteStation> result = service.routeStations(
            ROUTE_5003A, CallBudget.of(20), NOW.plusHours(10)
        );

        assertThat(result).isEqualTo(STATIONS);
        verify(gbisClient, times(1)).getRouteStations(ROUTE_5003A);
    }

    @Test
    void 새벽_4시가_지나면_새로_받음() {
        when(gbisClient.getRouteStations(ROUTE_5003A)).thenReturn(STATIONS);

        OffsetDateTime beforeReset =
            OffsetDateTime.parse("2026-11-05T03:59:00+09:00");
        OffsetDateTime afterReset =
            OffsetDateTime.parse("2026-11-05T04:00:00+09:00");

        service.routeStations(ROUTE_5003A, CallBudget.of(20), NOW);
        service.routeStations(ROUTE_5003A, CallBudget.of(20), beforeReset);
        verify(gbisClient, times(1)).getRouteStations(ROUTE_5003A);

        service.routeStations(ROUTE_5003A, CallBudget.of(20), afterReset);
        verify(gbisClient, times(2)).getRouteStations(ROUTE_5003A);
    }

    @Test
    void 호출이_실패하면_지난_캐시를_씀() {
        when(gbisClient.getRouteStations(ROUTE_5003A))
            .thenReturn(STATIONS)
            .thenThrow(new IllegalStateException("GBIS down"));

        service.routeStations(ROUTE_5003A, CallBudget.of(20), NOW);
        List<RouteStation> result = service.routeStations(
            ROUTE_5003A, CallBudget.of(20), NOW.plusDays(1)
        );

        assertThat(result).isEqualTo(STATIONS);
    }

    @Test
    void 캐시도_없이_실패하면_빈_목록() {
        when(gbisClient.getRouteStations(ROUTE_5003A))
            .thenThrow(new IllegalStateException("GBIS down"));

        assertThat(service.routeStations(ROUTE_5003A, CallBudget.of(20), NOW))
            .isEmpty();
    }

    @Test
    void 요청당_한도를_다_쓰면_부르지_않고_한도_도달_표시() {
        CallBudget budget = CallBudget.of(1);
        when(gbisClient.getStationRoutes(anyString())).thenReturn(List.of());

        service.stationRoutes(DONGBAEK, budget, NOW);
        service.stationRoutes(GANGNAM, budget, NOW);

        verify(gbisClient, times(1)).getStationRoutes(anyString());
        assertThat(budget.used()).isEqualTo(1);
        assertThat(budget.limitReached()).isTrue();
    }

    @Test
    void 재평가처럼_호출_불가면_캐시만_씀() {
        when(gbisClient.getRouteStations(ROUTE_5003A)).thenReturn(STATIONS);

        assertThat(service.routeStations(ROUTE_5003A, CallBudget.none(), NOW))
            .isEmpty();
        verify(gbisClient, never()).getRouteStations(anyString());

        service.routeStations(ROUTE_5003A, CallBudget.of(20), NOW);
        assertThat(service.routeStations(ROUTE_5003A, CallBudget.none(), NOW))
            .isEqualTo(STATIONS);
    }

    @Test
    void 하루_한도를_넘으면_부르지_않음() {
        service = new RouteStationService(gbisClient, new GbisCallLimiter(0));

        service.stationRoutes(DONGBAEK, CallBudget.of(20), NOW);

        verify(gbisClient, never()).getStationRoutes(anyString());
    }

    @Test
    void 대체노선은_승차와_하차_정류장을_모두_지나는_노선에서_현재_노선_제외() {
        when(gbisClient.getStationRoutes(DONGBAEK)).thenReturn(List.of(
            new StationRoute(ROUTE_5003A, "5003A"),
            new StationRoute("228000175", "5005"),
            new StationRoute("228000174", "5000")
        ));
        when(gbisClient.getStationRoutes(GANGNAM)).thenReturn(List.of(
            new StationRoute(ROUTE_5003A, "5003A"),
            new StationRoute("228000175", "5005")
        ));

        List<CommonRoute> result = service.commonRoutes(
            DONGBAEK, List.of(GANGNAM), Set.of(ROUTE_5003A), CallBudget.of(20), NOW
        );

        assertThat(result)
            .containsExactly(new CommonRoute("228000175", "5005", GANGNAM));
        verify(gbisClient, times(2)).getStationRoutes(anyString());
    }

    @Test
    void 대체노선은_다른_승강장에_서는_노선도_그_승강장으로_찾음() {
        when(gbisClient.getStationRoutes(DONGBAEK)).thenReturn(List.of(
            new StationRoute(ROUTE_5003A, "5003A"),
            new StationRoute("228000182", "5003B"),
            new StationRoute("228000175", "5005")
        ));
        when(gbisClient.getStationRoutes(GANGNAM)).thenReturn(List.of(
            new StationRoute(ROUTE_5003A, "5003A"),
            new StationRoute("228000175", "5005")
        ));
        when(gbisClient.getStationRoutes(GANGNAM_B)).thenReturn(List.of(
            new StationRoute("228000182", "5003B"),
            new StationRoute("228000175", "5005")
        ));

        List<CommonRoute> result = service.commonRoutes(
            DONGBAEK, List.of(GANGNAM, GANGNAM_B), Set.of(ROUTE_5003A),
            CallBudget.of(20), NOW
        );

        // 원래 승강장에 서는 노선은 원래 승강장으로
        assertThat(result).containsExactly(
            new CommonRoute("228000175", "5005", GANGNAM),
            new CommonRoute("228000182", "5003B", GANGNAM_B)
        );
    }

    @Test
    void 같은_정류장은_300m_안에서_이름이_같은_정류장() {
        when(gbisClient.getStationsAround(127.02857, 37.49578)).thenReturn(List.of(
            new AroundStation(GANGNAM, "신분당선강남역(중)", 0),
            new AroundStation("121000974", "강남역우리은행", 20),
            new AroundStation(GANGNAM_B, "신분당선강남역(중) ", 102),
            new AroundStation("121000099", "신분당선강남역(중)", 350)
        ));
        RouteStation gangnam =
            new RouteStation(GANGNAM, "신분당선강남역(중)", 57, 127.02857, 37.49578);

        assertThat(service.sameStations(gangnam, CallBudget.of(20), NOW))
            .containsExactly(GANGNAM, GANGNAM_B);

        // 하루 동안 캐시
        service.sameStations(gangnam, CallBudget.of(20), NOW.plusHours(1));
        verify(gbisClient, times(1)).getStationsAround(127.02857, 37.49578);
    }

    @Test
    void 좌표가_없으면_같은_정류장은_자기_자신만() {
        RouteStation noLocation = new RouteStation(GANGNAM, "강남", 57, null, null);

        assertThat(service.sameStations(noLocation, CallBudget.of(20), NOW))
            .containsExactly(GANGNAM);
        verify(gbisClient, never()).getStationsAround(anyDouble(), anyDouble());
    }

    @Test
    void 회차_노선은_하차_정류장보다_앞이면서_가장_가까운_순번() {
        List<RouteStation> loop = List.of(
            new RouteStation("A", "출발", 1, null, null),
            new RouteStation(DONGBAEK, "동백2차", 5, null, null),
            new RouteStation("TURN", "회차", 30, null, null),
            new RouteStation(DONGBAEK, "동백2차", 50, null, null),
            new RouteStation(GANGNAM, "강남", 60, null, null)
        );

        assertThat(RouteStationService.boardSeq(loop, DONGBAEK, GANGNAM))
            .isEqualTo(50);
        assertThat(RouteStationService.boardSeq(loop, DONGBAEK, "없음"))
            .isEqualTo(5);
        assertThat(RouteStationService.boardSeq(loop, "없음", GANGNAM))
            .isNull();
    }
}
