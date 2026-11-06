package com.yongyong.transit.analysis.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 버스 구간 하나의 탑승 단계와 탔을 때/못 탔을 때 도착 시각을 계산한다.
 * 외부 호출 없이 계산만 하며, 분석 API와 실시간 재평가가 함께 사용한다.
 */
@Component
public class SegmentAnalyzer {

    private static final long SLACK_OK_MIN = 5;
    private static final long SLACK_CAUTION_MIN = 2;
    private static final int SEAT_OK_MIN = 5;
    private static final long STALE_SECONDS = 240;

    public SegmentResult analyze(SegmentInput input) {
        OffsetDateTime readyAt = input.baseTime()
            .plusMinutes(input.walkMin());

        List<BusArrival> candidates = input.arrivals()
            .stream()
            .filter(arrival -> arrival.arrivalAt() != null)
            .filter(arrival -> !arrival.arrivalAt().isBefore(readyAt))
            .sorted(Comparator.comparing(BusArrival::arrivalAt))
            .toList();

        if (candidates.isEmpty()) {
            return noArrival(input);
        }

        BusArrival board = candidates.get(0);
        BusArrival next = candidates.size() > 1
            ? candidates.get(1)
            : null;

        boolean stale = isStale(board.dataBasisAt(), input.now());

        long slackMin = Duration
            .between(readyAt, board.arrivalAt())
            .toMinutes();

        Stage slackStage = stale
            ? Stage.UNKNOWN
            : slackStage(slackMin);

        Stage seatStage = stale
            ? Stage.UNKNOWN
            : seatStage(board.seatType(), board.remainSeats());

        OffsetDateTime finalIfBoard = finalArrival(input, board);
        OffsetDateTime finalIfMiss = next == null
            ? null
            : finalArrival(input, next);

        return new SegmentResult(
            input.segmentIndex(),
            board.routeLabel(),
            input.boardName(),
            board.arrivalAt(),
            (int) slackMin,
            slackStage,
            board.remainSeats(),
            board.seatType(),
            seatStage,
            boardingStage(slackStage, seatStage),
            arriveAt(input, board),
            minutesBetween(input.now(), finalIfBoard),
            next == null ? null : arriveAt(input, next),
            next == null ? null : minutesBetween(input.now(), finalIfMiss),
            next == null ? null : next.routeLabel(),
            next == null ? null : next.arrivalAt(),
            board.dataBasisAt(),
            board.plateNo(),
            board.modelVersion()
        );
    }

    private SegmentResult noArrival(SegmentInput input) {
        return new SegmentResult(
            input.segmentIndex(),
            input.routeLabel(),
            input.boardName(),
            null,
            null,
            Stage.UNKNOWN,
            null,
            SeatType.NONE,
            Stage.UNKNOWN,
            Stage.CAUTION,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    // 앞 버스 구간은 하차 시각, 마지막 버스 구간은 최종 도착 시각
    private OffsetDateTime arriveAt(
        SegmentInput input,
        BusArrival arrival
    ) {
        OffsetDateTime alightAt = arrival.arrivalAt()
            .plusMinutes(input.durationMin());

        return input.lastBusSegment()
            ? alightAt.plusMinutes(input.afterMin())
            : alightAt;
    }

    // 총 소요 계산용: 구간 위치와 상관없이 최종 목적지 도착 시각
    private OffsetDateTime finalArrival(
        SegmentInput input,
        BusArrival arrival
    ) {
        return arrival.arrivalAt()
            .plusMinutes(input.durationMin())
            .plusMinutes(input.afterMin());
    }

    private Stage slackStage(long slackMin) {
        if (slackMin >= SLACK_OK_MIN) {
            return Stage.OK;
        }

        if (slackMin >= SLACK_CAUTION_MIN) {
            return Stage.CAUTION;
        }

        return Stage.RISK;
    }

    private Stage seatStage(
        SeatType seatType,
        Integer remainSeats
    ) {
        if (seatType != SeatType.PREDICTED || remainSeats == null) {
            return Stage.UNKNOWN;
        }

        if (remainSeats >= SEAT_OK_MIN) {
            return Stage.OK;
        }

        if (remainSeats >= 1) {
            return Stage.CAUTION;
        }

        return Stage.RISK;
    }

    // 더 위험한 쪽을 고르고, 둘 다 UNKNOWN이면 안전하다고 보지 않고 CAUTION
    private Stage boardingStage(
        Stage slackStage,
        Stage seatStage
    ) {
        if (slackStage == Stage.UNKNOWN && seatStage == Stage.UNKNOWN) {
            return Stage.CAUTION;
        }

        if (slackStage == Stage.UNKNOWN) {
            return seatStage;
        }

        if (seatStage == Stage.UNKNOWN) {
            return slackStage;
        }

        return Stage.worse(slackStage, seatStage);
    }

    private boolean isStale(
        OffsetDateTime dataBasisAt,
        OffsetDateTime now
    ) {
        if (dataBasisAt == null) {
            return true;
        }

        return Duration.between(dataBasisAt, now).getSeconds()
            >= STALE_SECONDS;
    }

    private Integer minutesBetween(
        OffsetDateTime from,
        OffsetDateTime to
    ) {
        if (to == null) {
            return null;
        }

        return (int) Duration.between(from, to).toMinutes();
    }

    public enum Stage {
        OK,
        CAUTION,
        RISK,
        UNKNOWN;

        public static Stage worse(Stage a, Stage b) {
            return a.ordinal() >= b.ordinal() ? a : b;
        }
    }

    public enum SeatType {
        PREDICTED,
        OBSERVED,
        NONE
    }

    public record BusArrival(
        String routeId,
        String routeLabel,
        String plateNo,
        OffsetDateTime arrivalAt,
        Integer remainSeats,
        SeatType seatType,
        OffsetDateTime dataBasisAt,
        String modelVersion
    ) {
    }

    public record SegmentInput(
        int segmentIndex,
        String routeLabel,
        String boardName,
        OffsetDateTime baseTime,
        int walkMin,
        int durationMin,
        int afterMin,
        boolean lastBusSegment,
        List<BusArrival> arrivals,
        OffsetDateTime now
    ) {
    }

    public record SegmentResult(
        int segmentIndex,
        String routeLabel,
        String boardName,
        OffsetDateTime predictedBusArrivalAt,
        Integer slackMin,
        Stage slackStage,
        Integer remainSeats,
        SeatType seatType,
        Stage seatStage,
        Stage boardingStage,
        OffsetDateTime arriveIfBoard,
        Integer totalMinIfBoard,
        OffsetDateTime arriveIfMiss,
        Integer totalMinIfMiss,
        String nextRouteLabel,
        OffsetDateTime nextBusArrivalAt,
        OffsetDateTime dataBasisAt,
        String plateNo,
        String modelVersion
    ) {
    }
}
