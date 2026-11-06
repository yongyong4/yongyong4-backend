package com.yongyong.transit.analysis.client;

import com.yongyong.transit.analysis.client.PredictClient.PredictArrival;
import com.yongyong.transit.analysis.client.PredictClient.PredictRequest;
import com.yongyong.transit.analysis.client.PredictClient.PredictResult;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PredictClientTest {

    private final PredictClient client = new PredictClient("http://localhost:8090/");

    @Test
    void URL이_없으면_사용하지_않음() {
        assertThat(new PredictClient("").isEnabled()).isFalse();
        assertThat(client.isEnabled()).isTrue();
    }

    @Test
    void 요청은_명세_형식() {
        String json = client.toJson(new PredictRequest(
            "228000175",
            "228000172",
            OffsetDateTime.parse("2026-11-04T07:30:00+09:00"),
            List.of(
                new PredictArrival(
                    "경기70바1234",
                    OffsetDateTime.parse("2026-11-04T07:40:00+09:00"),
                    5
                ),
                new PredictArrival(
                    "경기70바5678",
                    OffsetDateTime.parse("2026-11-04T07:58:00+09:00"),
                    null
                )
            )
        ));

        assertThat(json)
            .contains("\"gbisRouteId\":\"228000175\"")
            .contains("\"boardStationId\":\"228000172\"")
            .contains("\"requestAt\":\"2026-11-04T07:30:00+09:00\"")
            .contains("\"predictedBusArrivalAt\":\"2026-11-04T07:40:00+09:00\"")
            .contains("\"remainSeats\":5")
            .contains("\"remainSeats\":null");
    }

    @Test
    void 응답_해석() {
        PredictResult result = client.parse("""
            {"predictions":[
              {"plateNo":"경기70바1234","predictedBusArrivalAt":"2026-11-04T07:41:00+09:00","predictedRemainSeats":3},
              {"plateNo":"경기70바5678","predictedBusArrivalAt":"2026-11-04T07:59:00+09:00","predictedRemainSeats":null}
            ],"modelVersion":"v1"}
            """);

        assertThat(result.modelVersion()).isEqualTo("v1");
        assertThat(result.predictions()).hasSize(2);
        assertThat(result.predictions().get(0).predictedBusArrivalAt())
            .isEqualTo(OffsetDateTime.parse("2026-11-04T07:41:00+09:00"));
        assertThat(result.predictions().get(0).predictedRemainSeats()).isEqualTo(3);
        assertThat(result.predictions().get(1).predictedRemainSeats()).isNull();
    }

    @Test
    void 형식이_틀린_응답은_예외() {
        assertThatThrownBy(() -> client.parse("{\"error\":\"model\"}"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.parse("<html>"))
            .isInstanceOf(IllegalStateException.class);
    }
}
