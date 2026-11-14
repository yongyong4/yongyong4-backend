package com.yongyong.transit.route.service;

import com.yongyong.transit.route.client.OdsayClient;
import com.yongyong.transit.route.service.RouteSearchService.SegmentInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RouteSearchServiceTest {

    // 2026-11-11 동백 → 강남 ODsay 실제 응답에서 구간만 골라 줄인 값
    private static final String ODSAY_BODY = """
            {"result":{"path":[
              {"info":{"totalTime":67,"totalDistance":39830,"busTransitCount":1,"subwayTransitCount":0},
               "subPath":[
                {"trafficType":3,"distance":268,"sectionTime":4},
                {"trafficType":2,"distance":39562,"sectionTime":63,
                 "startName":"동백2동행정복지센터","endName":"신분당선강남역",
                 "startX":127.151703,"startY":37.272129,"endX":127.028595,"endY":37.495849,
                 "startLocalStationID":"228000171","endLocalStationID":"121000009",
                 "passStopList":{"stations":[
                   {"index":0,"stationName":"동백2동행정복지센터","x":"127.151703","y":"37.272129"},
                   {"index":1,"stationName":"좌표없음"},
                   {"index":2,"stationName":"신분당선강남역","x":"127.028595","y":"37.495849"}]},
                 "lane":[{"busNo":"5003A","type":4,"busLocalBlID":"228000431"},
                         {"busNo":"33-2","type":3,"busLocalBlID":"241424015"}]}
               ]},
              {"info":{"totalTime":39,"totalDistance":32100,"busTransitCount":0,"subwayTransitCount":3},
               "subPath":[
                {"trafficType":1,"distance":4200,"sectionTime":6,"startName":"동백","endName":"기흥",
                 "startX":127.152718,"startY":37.269109,"endX":127.116744,"endY":37.275469,
                 "lane":[{"name":"수도권 에버라인","subwayCode":107}]},
                {"trafficType":1,"distance":10600,"sectionTime":18,"startName":"기흥","endName":"정자",
                 "startX":127.115953,"startY":37.275644,"endX":127.108086,"endY":37.366162,
                 "lane":[{"name":"수도권 수인.분당선","subwayCode":116}]},
                {"trafficType":1,"distance":17300,"sectionTime":15,"startName":"정자","endName":"강남",
                 "startX":127.108451,"startY":37.367724,"endX":127.028363,"endY":37.49637,
                 "lane":[{"name":"수도권 신분당선","subwayCode":109}]}
               ]}
            ]}}
            """;

    private RouteSearchService service;

    @BeforeEach
    void setUp() {
        OdsayClient odsayClient = mock(OdsayClient.class);
        when(odsayClient.searchRoutes(
                anyDouble(), anyDouble(), anyDouble(), anyDouble()
        )).thenReturn(ODSAY_BODY);

        service = new RouteSearchService(
                odsayClient,
                JsonMapper.builder().build()
        );
    }

    @Test
    void busSegmentHasStopCoordinatesAndBusType() {
        SegmentInfo bus = search().get(0).segments().get(1);

        assertThat(bus.startX()).isEqualTo(127.151703);
        assertThat(bus.startY()).isEqualTo(37.272129);
        assertThat(bus.endX()).isEqualTo(127.028595);
        assertThat(bus.endY()).isEqualTo(37.495849);
        assertThat(bus.lineName()).isNull();

        assertThat(bus.busOptions())
                .extracting(RouteSearchService.BusOption::busType)
                .containsExactly("광역", "마을");
    }

    @Test
    void busSegmentHasPassStopsInOrder() {
        SegmentInfo bus = search().get(0).segments().get(1);

        assertThat(bus.passStops())
                .extracting(RouteSearchService.PassStop::name)
                .containsExactly("동백2동행정복지센터", "신분당선강남역");
        assertThat(bus.passStops().get(0).x()).isEqualTo(127.151703);
        assertThat(bus.passStops().get(0).y()).isEqualTo(37.272129);
    }

    @Test
    void walkSegmentHasNoCoordinates() {
        SegmentInfo walk = search().get(0).segments().get(0);

        assertThat(walk.startX()).isNull();
        assertThat(walk.endY()).isNull();
        assertThat(walk.lineName()).isNull();
        assertThat(walk.busOptions()).isEmpty();
        assertThat(walk.passStops()).isEmpty();
    }

    @Test
    void subwaySegmentsHaveLineName() {
        List<SegmentInfo> subway = search().get(1).segments();

        assertThat(subway)
                .extracting(SegmentInfo::lineName)
                .containsExactly("에버라인", "수인분당", "신분당선");
        assertThat(subway.get(0).startX()).isEqualTo(127.152718);
    }

    @Test
    void busTypeMapsOdsayCodes() {
        assertThat(RouteSearchService.busType(4)).isEqualTo("광역");
        assertThat(RouteSearchService.busType(14)).isEqualTo("광역");
        assertThat(RouteSearchService.busType(3)).isEqualTo("마을");
        assertThat(RouteSearchService.busType(1)).isEqualTo("일반");
        assertThat(RouteSearchService.busType(null)).isEqualTo("일반");
    }

    @Test
    void lineNameDropsMetroPrefix() {
        assertThat(RouteSearchService.lineName(2, "수도권 2호선"))
                .isEqualTo("2호선");
        assertThat(RouteSearchService.lineName(null, null)).isNull();
    }

    private List<RouteSearchService.RouteInfo> search() {
        return service.search(127.1497, 37.2690, 127.0276, 37.4979)
                .routes();
    }
}
