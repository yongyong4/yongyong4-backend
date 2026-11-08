package com.yongyong.transit.analysis.client;

import com.yongyong.transit.analysis.client.GbisClient.AroundStation;
import com.yongyong.transit.analysis.client.GbisClient.GbisArrivals;
import com.yongyong.transit.analysis.client.GbisClient.GbisBus;
import com.yongyong.transit.analysis.client.GbisClient.RouteStation;
import com.yongyong.transit.analysis.client.GbisClient.StationRoute;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GbisClientTest {

    private final GbisClient client = new GbisClient("test-key");

    @Test
    void 노선별_다음버스_2대를_풀어서_반환() {
        GbisArrivals result = client.parse("""
            {"response":{
              "msgHeader":{"resultCode":0,"queryTime":"2026-11-04 07:29:00.123"},
              "msgBody":{"busArrivalList":[
                {"routeId":228000175,"routeName":"5005",
                 "plateNo1":"경기70바1234","predictTime1":10,"predictTimeSec1":615,"remainSeatCnt1":5,
                 "plateNo2":"경기70바5678","predictTime2":28,"predictTimeSec2":"","remainSeatCnt2":-1},
                {"routeId":"228000431","routeName":"5003A",
                 "plateNo1":"경기70바9999","predictTime1":3,"remainSeatCnt1":12,
                 "plateNo2":"","predictTime2":"","remainSeatCnt2":""}
              ]}
            }}
            """);

        assertThat(result.queryTime())
            .isEqualTo(OffsetDateTime.parse("2026-11-04T07:29:00.123+09:00"));
        assertThat(result.buses()).hasSize(3);

        GbisBus first = result.buses().get(0);
        assertThat(first.routeId()).isEqualTo("228000175");
        assertThat(first.plateNo()).isEqualTo("경기70바1234");
        assertThat(first.predictSec()).isEqualTo(615);
        assertThat(first.remainSeats()).isEqualTo(5);

        // 초 단위 값이 없으면 분 단위 값을 쓴다
        assertThat(result.buses().get(1).predictSec()).isEqualTo(28 * 60);
        assertThat(result.buses().get(1).remainSeats()).isEqualTo(-1);
        assertThat(result.buses().get(2).routeName()).isEqualTo("5003A");
    }

    @Test
    void 항목이_하나면_배열이_아닌_객체로_와도_처리() {
        GbisArrivals result = client.parse("""
            {"response":{
              "msgHeader":{"resultCode":"0","queryTime":"2026-11-04 07:29:00"},
              "msgBody":{"busArrivalList":
                {"routeId":"228000175","routeName":"5005","plateNo1":"A","predictTime1":5}
              }
            }}
            """);

        assertThat(result.buses()).hasSize(1);
    }

    @Test
    void resultCode_4는_운행버스_없음으로_빈목록() {
        GbisArrivals result = client.parse("""
            {"response":{"msgHeader":{"resultCode":4,"queryTime":"2026-11-04 07:29:00"}}}
            """);

        assertThat(result.buses()).isEmpty();
    }

    @Test
    void 게이트웨이_오류와_비정상_resultCode는_예외() {
        assertThatThrownBy(() -> client.parse("""
            {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"returnReasonCode":"22"}}}
            """)).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> client.parse("""
            {"response":{"msgHeader":{"resultCode":8}}}
            """)).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> client.parse("<html>error</html>"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 노선_경유_정류장_목록_파싱() {
        List<RouteStation> result = client.parseRouteStations("""
            {"response":{
              "msgHeader":{"resultCode":0},
              "msgBody":{"busRouteStationList":[
                {"stationId":228000168,"stationName":"동백쥬네브","stationSeq":11,"x":127.1501,"y":37.2712},
                {"stationId":"228000172","stationName":"동백2차","stationSeq":12,"x":"127.1526","y":"37.2691"},
                {"stationId":"","stationName":"ID없음","stationSeq":13}
              ]}
            }}
            """);

        assertThat(result).containsExactly(
            new RouteStation("228000168", "동백쥬네브", 11, 127.1501, 37.2712),
            new RouteStation("228000172", "동백2차", 12, 127.1526, 37.2691)
        );
    }

    @Test
    void 정류장_경유_노선_파싱_숫자_노선명과_단일_객체() {
        List<StationRoute> result = client.parseStationRoutes("""
            {"response":{
              "msgHeader":{"resultCode":0},
              "msgBody":{"busRouteList":
                {"routeId":228000174,"routeName":5000}
              }
            }}
            """);

        assertThat(result).containsExactly(
            new StationRoute("228000174", "5000")
        );
    }

    @Test
    void 주변_정류장_파싱_거리없는_항목은_제외() {
        List<AroundStation> result = client.parseStationsAround("""
            {"response":{
              "msgHeader":{"resultCode":0},
              "msgBody":{"busStationAroundList":[
                {"stationId":121000010,"stationName":"신분당선강남역(중)","distance":16},
                {"stationId":121000009,"stationName":"신분당선강남역(중)","distance":102},
                {"stationId":121000999,"stationName":"거리 없음"}
              ]}
            }}
            """);

        assertThat(result).containsExactly(
            new AroundStation("121000010", "신분당선강남역(중)", 16),
            new AroundStation("121000009", "신분당선강남역(중)", 102)
        );
    }

    @Test
    void 정적_정보_결과_없음이면_빈_목록() {
        assertThat(client.parseStationRoutes("""
            {"response":{"msgHeader":{"resultCode":4}}}
            """)).isEmpty();
        assertThat(client.parseRouteStations("""
            {"response":{"msgHeader":{"resultCode":4}}}
            """)).isEmpty();
    }

    @Test
    void 정적_정보_게이트웨이_오류면_예외() {
        assertThatThrownBy(() -> client.parseStationRoutes("""
            {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"SERVICE ERROR"}}}
            """)).isInstanceOf(IllegalStateException.class);
    }
}
