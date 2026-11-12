package com.yongyong.transit.analysis.service;

import com.yongyong.transit.analysis.service.SegmentAnalyzer.BusArrival;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SeatType;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SegmentInput;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SegmentResult;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.Stage;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentAnalyzerTest {

    // 명세 예시 기준: 07:30 현재, 도보 9분 → 07:39 정류장 도착
    private static final OffsetDateTime NOW =
        OffsetDateTime.parse("2026-11-04T07:30:00+09:00");

    private final SegmentAnalyzer analyzer = new SegmentAnalyzer();

    @Test
    void 명세_예시_여유2분_예측좌석3석이면_CAUTION과_타면_못타면_시각() {
        SegmentResult result = analyze(true, List.of(
            arrival("5005", "07:41", 3, SeatType.PREDICTED),
            arrival("5003A", "07:59", 18, SeatType.PREDICTED)
        ));

        assertThat(result.routeLabel()).isEqualTo("5005");
        assertThat(result.slackMin()).isEqualTo(2);
        assertThat(result.slackStage()).isEqualTo(Stage.CAUTION);
        assertThat(result.seatStage()).isEqualTo(Stage.CAUTION);
        assertThat(result.boardingStage()).isEqualTo(Stage.CAUTION);
        assertThat(result.arriveIfBoard()).isEqualTo(at("08:24"));
        assertThat(result.totalMinIfBoard()).isEqualTo(54);
        assertThat(result.nextRouteLabel()).isEqualTo("5003A");
        assertThat(result.nextBusArrivalAt()).isEqualTo(at("07:59"));
        assertThat(result.arriveIfMiss()).isEqualTo(at("08:42"));
        assertThat(result.totalMinIfMiss()).isEqualTo(72);
        assertThat(result.plateNo()).isEqualTo("plate-5005");
    }

    @Test
    void 여유시간_경계값() {
        assertThat(slackStageFor("07:44")).isEqualTo(Stage.OK);      // 5분
        assertThat(slackStageFor("07:43")).isEqualTo(Stage.CAUTION); // 4분
        assertThat(slackStageFor("07:41")).isEqualTo(Stage.CAUTION); // 2분
        assertThat(slackStageFor("07:40")).isEqualTo(Stage.RISK);    // 1분
    }

    @Test
    void 예측좌석_경계값() {
        assertThat(seatStageFor(5, SeatType.PREDICTED)).isEqualTo(Stage.OK);
        assertThat(seatStageFor(1, SeatType.PREDICTED)).isEqualTo(Stage.CAUTION);
        assertThat(seatStageFor(0, SeatType.PREDICTED)).isEqualTo(Stage.RISK);
    }

    @Test
    void 실시간좌석은_판단하지_않고_여유시간만으로_탑승단계() {
        SegmentResult result = analyze(true, List.of(
            arrival("5005", "07:45", 0, SeatType.OBSERVED)
        ));

        assertThat(result.remainSeats()).isEqualTo(0);
        assertThat(result.seatStage()).isEqualTo(Stage.UNKNOWN);
        assertThat(result.boardingStage()).isEqualTo(Stage.OK);
    }

    @Test
    void 여유는_OK여도_예측좌석이_0석이면_RISK() {
        SegmentResult result = analyze(true, List.of(
            arrival("5005", "07:50", 0, SeatType.PREDICTED)
        ));

        assertThat(result.slackStage()).isEqualTo(Stage.OK);
        assertThat(result.boardingStage()).isEqualTo(Stage.RISK);
    }

    @Test
    void 정류장_도착_전에_오는_버스는_제외() {
        SegmentResult result = analyze(true, List.of(
            arrival("5005", "07:35", 10, SeatType.OBSERVED),
            arrival("5003A", "07:47", 10, SeatType.OBSERVED)
        ));

        assertThat(result.routeLabel()).isEqualTo("5003A");
        assertThat(result.nextRouteLabel()).isNull();
    }

    @Test
    void 다음_버스가_없으면_못타면_값은_null() {
        SegmentResult result = analyze(true, List.of(
            arrival("5005", "07:45", 10, SeatType.OBSERVED)
        ));

        assertThat(result.nextRouteLabel()).isNull();
        assertThat(result.nextBusArrivalAt()).isNull();
        assertThat(result.arriveIfMiss()).isNull();
        assertThat(result.totalMinIfMiss()).isNull();
    }

    @Test
    void 오는_버스가_없으면_여유UNKNOWN_탑승CAUTION() {
        SegmentResult result = analyze(true, List.of());

        assertThat(result.routeLabel()).isEqualTo("5005");
        assertThat(result.predictedBusArrivalAt()).isNull();
        assertThat(result.slackStage()).isEqualTo(Stage.UNKNOWN);
        assertThat(result.seatType()).isEqualTo(SeatType.NONE);
        assertThat(result.boardingStage()).isEqualTo(Stage.CAUTION);
        assertThat(result.arriveIfBoard()).isNull();
    }

    @Test
    void 데이터가_4분_이상_지나면_시각은_유지하고_단계만_UNKNOWN() {
        BusArrival stale = new BusArrival(
            "228000175", "5005", "plate", at("07:45"), 3,
            SeatType.PREDICTED, at("07:26"), "v1"
        );

        SegmentResult result = analyze(true, List.of(stale));

        assertThat(result.predictedBusArrivalAt()).isEqualTo(at("07:45"));
        assertThat(result.remainSeats()).isEqualTo(3);
        assertThat(result.slackStage()).isEqualTo(Stage.UNKNOWN);
        assertThat(result.seatStage()).isEqualTo(Stage.UNKNOWN);
        assertThat(result.boardingStage()).isEqualTo(Stage.CAUTION);
    }

    @Test
    void 정류장에_있으면_여유는_OK지만_좌석과_오래된_데이터_판단은_유지() {
        SegmentResult soon = analyzeAtStop(arrival("5005", "07:31", 10, SeatType.OBSERVED));
        assertThat(soon.slackMin()).isEqualTo(1);
        assertThat(soon.slackStage()).isEqualTo(Stage.OK);
        assertThat(soon.boardingStage()).isEqualTo(Stage.OK);

        SegmentResult noSeat = analyzeAtStop(arrival("5005", "07:31", 0, SeatType.PREDICTED));
        assertThat(noSeat.boardingStage()).isEqualTo(Stage.RISK);

        SegmentResult stale = analyzeAtStop(new BusArrival(
            "228000175", "5005", "plate", at("07:31"), 10,
            SeatType.OBSERVED, at("07:26"), "v1"
        ));
        assertThat(stale.slackStage()).isEqualTo(Stage.UNKNOWN);
    }

    @Test
    void 마지막이_아닌_버스구간은_하차시각과_목적지까지_총소요() {
        SegmentResult result = analyze(false, List.of(
            arrival("5005", "07:41", 10, SeatType.OBSERVED)
        ));

        // 하차 07:41 + 39분 = 08:20, 목적지는 + 4분 = 08:24
        assertThat(result.arriveIfBoard()).isEqualTo(at("08:20"));
        assertThat(result.totalMinIfBoard()).isEqualTo(54);
    }

    private Stage slackStageFor(String arrivalTime) {
        return analyze(true, List.of(
            arrival("5005", arrivalTime, null, SeatType.NONE)
        )).slackStage();
    }

    private Stage seatStageFor(Integer seats, SeatType seatType) {
        return analyze(true, List.of(
            arrival("5005", "07:50", seats, seatType)
        )).seatStage();
    }

    // 도보 9분, 버스 39분, 하차 후 4분
    private SegmentResult analyze(
        boolean lastBusSegment,
        List<BusArrival> arrivals
    ) {
        return analyzer.analyze(new SegmentInput(
            1, "5005", "동백2차",
            NOW, 9, 39, 4, lastBusSegment,
            arrivals, NOW, false
        ));
    }

    // 이미 정류장에 있음: 도보 0분, 버스 39분, 하차 후 4분
    private SegmentResult analyzeAtStop(BusArrival arrival) {
        return analyzer.analyze(new SegmentInput(
            1, "5005", "동백2차",
            NOW, 0, 39, 4, true,
            List.of(arrival), NOW, true
        ));
    }

    private BusArrival arrival(
        String label,
        String time,
        Integer seats,
        SeatType seatType
    ) {
        return new BusArrival(
            "route-" + label, label, "plate-" + label, at(time), seats,
            seatType, at("07:29"), "v1"
        );
    }

    private OffsetDateTime at(String hhmm) {
        return OffsetDateTime.parse("2026-11-04T" + hhmm + ":00+09:00");
    }
}
