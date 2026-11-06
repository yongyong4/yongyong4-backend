package com.yongyong.transit.analysis.service;

import com.yongyong.transit.analysis.client.GbisClient;
import com.yongyong.transit.analysis.client.GbisClient.GbisArrivals;
import com.yongyong.transit.analysis.client.GbisClient.GbisBus;
import com.yongyong.transit.analysis.service.BusRealtimeService.CollectorSnapshot;
import com.yongyong.transit.analysis.service.BusRealtimeService.StationArrivals;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.BusArrival;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SeatType;
import com.yongyong.transit.collector.client.CollectorClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusRealtimeServiceTest {

    private static final String STATION = "228000172";
    private static final String ROUTE_5005 = "228000175";
    private static final String ROUTE_5003A = "228000431";
    private static final OffsetDateTime NOW =
        OffsetDateTime.parse("2026-11-04T07:30:00+09:00");

    private final JsonMapper json = JsonMapper.builder().build();

    private GbisClient gbisClient;
    private CollectorClient collectorClient;
    private BusRealtimeService service;

    @BeforeEach
    void setUp() {
        gbisClient = mock(GbisClient.class);
        collectorClient = mock(CollectorClient.class);
        when(gbisClient.hasKey()).thenReturn(true);

        service = new BusRealtimeService(
            gbisClient,
            new GbisCallLimiter(1000),
            collectorClient
        );
    }

    @Test
    void 수집노선_수집정류장_4분이내면_수집서버값을_쓰고_GBIS는_부르지_않음() {
        givenCollector("2026-11-04T07:28:00+09:00");

        StationArrivals result = service.getArrivals(
            STATION, Set.of(ROUTE_5005),
            service.loadCollectorSnapshot(NOW), NOW
        );

        assertThat(result.arrivals()).hasSize(1);
        BusArrival arrival = result.arrivals().get(0);
        assertThat(arrival.arrivalAt())
            .isEqualTo(OffsetDateTime.parse("2026-11-04T07:38:00+09:00"));
        assertThat(arrival.remainSeats()).isEqualTo(7);
        assertThat(arrival.seatType()).isEqualTo(SeatType.OBSERVED);
        assertThat(result.dataBasisAt())
            .isEqualTo(OffsetDateTime.parse("2026-11-04T07:28:00+09:00"));
        verify(gbisClient, never()).getStationArrivals(anyString());
    }

    @Test
    void 수집값이_4분_넘게_지났으면_GBIS_호출() {
        givenCollector("2026-11-04T07:25:00+09:00");
        givenGbis(gbisArrivals());

        StationArrivals result = service.getArrivals(
            STATION, Set.of(ROUTE_5005),
            service.loadCollectorSnapshot(NOW), NOW
        );

        verify(gbisClient).getStationArrivals(STATION);
        assertThat(result.arrivals()).extracting(BusArrival::plateNo)
            .containsExactly("G-5005-1", "G-5005-2");
    }

    @Test
    void 수집서버에_없는_노선은_같은_정류장이어도_GBIS로_조회() {
        givenCollector("2026-11-04T07:28:00+09:00");
        givenGbis(gbisArrivals());

        StationArrivals result = service.getArrivals(
            STATION, Set.of(ROUTE_5005, ROUTE_5003A),
            service.loadCollectorSnapshot(NOW), NOW
        );

        // 5005는 수집 서버, 5003A는 GBIS
        assertThat(result.arrivals()).extracting(BusArrival::plateNo)
            .containsExactly("C-5005", "G-5003A");
    }

    @Test
    void GBIS_변환_좌석_마이너스1은_null_지난버스는_제외() {
        givenGbis(gbisArrivals());

        StationArrivals result = service.getArrivals(
            STATION, Set.of(ROUTE_5005),
            CollectorSnapshot.empty(), NOW
        );

        // 07:29 기준 + 600초 = 07:39, + 1500초 = 07:54
        assertThat(result.arrivals()).hasSize(2);
        assertThat(result.arrivals().get(0).arrivalAt())
            .isEqualTo(OffsetDateTime.parse("2026-11-04T07:39:00+09:00"));
        assertThat(result.arrivals().get(1).remainSeats()).isNull();
        assertThat(result.arrivals().get(1).seatType()).isEqualTo(SeatType.NONE);
        assertThat(result.dataBasisAt())
            .isEqualTo(OffsetDateTime.parse("2026-11-04T07:29:00+09:00"));
        assertThat(result.delayed()).isFalse();
    }

    @Test
    void 지난_버스는_제외() {
        givenGbis(new GbisArrivals(
            OffsetDateTime.parse("2026-11-04T07:20:00+09:00"),
            List.of(new GbisBus(ROUTE_5005, "5005", "OLD", 300, 10))
        ));

        StationArrivals result = service.getArrivals(
            STATION, Set.of(ROUTE_5005),
            CollectorSnapshot.empty(), NOW
        );

        assertThat(result.arrivals()).isEmpty();
    }

    @Test
    void 캐시_60초_이내면_GBIS를_다시_부르지_않음() {
        givenGbis(gbisArrivals());

        service.getArrivals(STATION, Set.of(ROUTE_5005), CollectorSnapshot.empty(), NOW);
        StationArrivals second = service.getArrivals(
            STATION, Set.of(ROUTE_5005),
            CollectorSnapshot.empty(), NOW.plusSeconds(59)
        );

        verify(gbisClient, times(1)).getStationArrivals(STATION);
        assertThat(second.delayed()).isFalse();
    }

    @Test
    void 캐시가_만료된_뒤_호출이_실패하면_마지막_캐시와_지연표시() {
        givenGbis(gbisArrivals());
        service.getArrivals(STATION, Set.of(ROUTE_5005), CollectorSnapshot.empty(), NOW);

        when(gbisClient.getStationArrivals(STATION))
            .thenThrow(new IllegalStateException("down"));

        StationArrivals result = service.getArrivals(
            STATION, Set.of(ROUTE_5005),
            CollectorSnapshot.empty(), NOW.plusSeconds(61)
        );

        verify(gbisClient, times(2)).getStationArrivals(STATION);
        assertThat(result.arrivals()).isNotEmpty();
        assertThat(result.delayed()).isTrue();
    }

    @Test
    void 키가_없으면_호출하지_않고_빈목록과_지연표시() {
        when(gbisClient.hasKey()).thenReturn(false);

        StationArrivals result = service.getArrivals(
            STATION, Set.of(ROUTE_5005),
            CollectorSnapshot.empty(), NOW
        );

        verify(gbisClient, never()).getStationArrivals(anyString());
        assertThat(result.arrivals()).isEmpty();
        assertThat(result.dataBasisAt()).isNull();
        assertThat(result.delayed()).isTrue();
    }

    @Test
    void 수집서버_오류면_빈_스냅샷() {
        when(collectorClient.getRoutes())
            .thenThrow(new IllegalStateException("down"));

        assertThat(service.loadCollectorSnapshot(NOW).arrivals(STATION))
            .isEmpty();
    }

    private void givenCollector(String collectedAt) {
        when(collectorClient.getRoutes()).thenReturn(json.readTree("""
            [{"route_id":"228000175","route_name":"5005"}]
            """));
        when(collectorClient.getArrivals()).thenReturn(json.readTree("""
            [{"station_id":"228000172","route_name":"5005","plate_no":"C-5005",
              "predict_time_min":10,"remain_seat_cnt":7,"collected_at":"%s"}]
            """.formatted(collectedAt)));
    }

    private void givenGbis(GbisArrivals arrivals) {
        when(gbisClient.getStationArrivals(STATION)).thenReturn(arrivals);
    }

    private GbisArrivals gbisArrivals() {
        return new GbisArrivals(
            OffsetDateTime.parse("2026-11-04T07:29:00+09:00"),
            List.of(
                new GbisBus(ROUTE_5005, "5005", "G-5005-1", 600, 5),
                new GbisBus(ROUTE_5005, "5005", "G-5005-2", 1500, -1),
                new GbisBus(ROUTE_5003A, "5003A", "G-5003A", 900, 12)
            )
        );
    }
}
