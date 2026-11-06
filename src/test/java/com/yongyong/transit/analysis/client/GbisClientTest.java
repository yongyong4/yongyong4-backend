package com.yongyong.transit.analysis.client;

import com.yongyong.transit.analysis.client.GbisClient.GbisArrivals;
import com.yongyong.transit.analysis.client.GbisClient.GbisBus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

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
}
