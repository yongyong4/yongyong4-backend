package com.yongyong.transit.strategy.service;

import com.yongyong.transit.analysis.service.RouteAnalysisService.SegmentView;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SeatType;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.Stage;
import com.yongyong.transit.common.client.OpenAiClient;
import com.yongyong.transit.strategy.service.ReasonService.Fact;
import com.yongyong.transit.strategy.service.StrategyService.Mode;
import com.yongyong.transit.strategy.service.StrategyService.StrategyType;
import com.yongyong.transit.strategy.service.StrategyService.StrategyView;
import com.yongyong.transit.strategy.service.StrategyService.TripRoute;
import com.yongyong.transit.strategy.service.StrategyService.TripSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReasonServiceTest {

    private static final OffsetDateTime NOW =
        OffsetDateTime.parse("2026-11-04T07:30:00+09:00");

    private OpenAiClient openAi;
    private ReasonService service;

    @BeforeEach
    void setUp() {
        openAi = mock(OpenAiClient.class);
        when(openAi.isEnabled()).thenReturn(true);
        service = new ReasonService(openAi);
    }

    @Test
    void LLM이_규칙을_지키면_그_문장을_씀() {
        givenLlm("""
            {"ALT_ROUTE": ["5003A번으로 바꾸면 9분 빨리 도착해요", "자리도 넉넉해요"],
             "KEEP": ["타려는 버스에 빈자리가 거의 없어요"]}
            """);

        List<StrategyView> result = service.withReasons(
            List.of(altRoute(true, 71), keep(false, 80, Stage.CAUTION)),
            Mode.STRATEGY, NOW
        );

        assertThat(result.get(0).reasons())
            .containsExactly("5003A번으로 바꾸면 9분 빨리 도착해요", "자리도 넉넉해요");
        assertThat(result.get(1).reasons())
            .containsExactly("타려는 버스에 빈자리가 거의 없어요");
    }

    @Test
    void 규칙을_어긴_전략만_문장_틀로_바꿈() {
        // KEEP: 사실에 없는 숫자(15)와 비추천인데 2문장
        givenLlm("""
            {"ALT_ROUTE": ["5003A번으로 바꾸면 9분 빨리 도착해요"],
             "KEEP": ["15분 늦어질 수 있어요", "자리가 없어요"]}
            """);

        List<StrategyView> result = service.withReasons(
            List.of(altRoute(true, 71), keep(false, 80, Stage.CAUTION)),
            Mode.STRATEGY, NOW
        );

        assertThat(result.get(0).reasons())
            .containsExactly("5003A번으로 바꾸면 9분 빨리 도착해요");
        assertThat(result.get(1).reasons())
            .containsExactly("타려는 버스에 빈자리가 거의 없을 수 있어요");
    }

    @Test
    void LLM이_실패하거나_늦으면_문장_틀() {
        when(openAi.completeJson(anyString(), anyString(), any()))
            .thenReturn(Optional.empty());

        List<StrategyView> result = service.withReasons(
            List.of(altRoute(true, 71), keep(false, 80, Stage.CAUTION)),
            Mode.STRATEGY, NOW
        );

        // 추천 전략은 빨라지는 시간 + 좌석
        assertThat(result.get(0).reasons()).containsExactly(
            "5003A번으로 바꾸면 9분 빨리 도착해요",
            "빈자리가 넉넉할 것으로 예상돼요"
        );
    }

    @Test
    void 재평가는_LLM을_부르지_않음() {
        List<StrategyView> result = service.withReasons(
            List.of(keep(true, 80, Stage.OK)), Mode.REEVALUATION, NOW
        );

        assertThat(result.get(0).reasons()).containsExactly(
            "지금 경로로 여유 있게 탈 수 있어요",
            "빈자리가 넉넉할 것으로 예상돼요"
        );
        verify(openAi, never()).completeJson(anyString(), anyString(), any());
    }

    @Test
    void 전략이_하나면_LLM을_부르지_않음() {
        List<StrategyView> result = service.withReasons(
            List.of(keep(true, 80, Stage.OK)), Mode.STRATEGY, NOW
        );

        assertThat(result.get(0).reasons()).isNotEmpty();
        verify(openAi, never()).completeJson(anyString(), anyString(), any());
    }

    @Test
    void 지하철_뒤에_갈아타는_버스는_대기_시간을_보내지_않음() {
        assertThat(ReasonService.fact(keepWith("BUS"), 80, NOW).waitMin()).isEqualTo(11);
        assertThat(ReasonService.fact(keepWith("SUBWAY"), 80, NOW).waitMin()).isNull();
    }

    @Test
    void 문장_검사는_개수_40자_해요체_확률_숫자를_봄() {
        Set<String> numbers = Set.of("9", "5003");

        assertThat(ReasonService.valid(List.of("9분 빨리 도착해요."), false, numbers, null)).isTrue();
        assertThat(ReasonService.valid(List.of(), false, numbers, null)).isFalse();
        assertThat(ReasonService.valid(List.of("a요", "b요"), false, numbers, null)).isFalse();
        assertThat(ReasonService.valid(List.of("9분 빨리 도착합니다"), false, numbers, null)).isFalse();
        assertThat(ReasonService.valid(List.of("탈 확률이 높아요"), false, numbers, null)).isFalse();
        assertThat(ReasonService.valid(List.of("10분 빨라요"), false, numbers, null)).isFalse();
        assertThat(ReasonService.valid(List.of("가".repeat(40) + "요"), false, numbers, null)).isFalse();
        // 내부 용어
        assertThat(ReasonService.valid(List.of("9분 빨라 추천해요"), false, numbers, null)).isFalse();
        assertThat(ReasonService.valid(List.of("주의 단계라 조심해요"), false, numbers, null)).isFalse();
        assertThat(ReasonService.valid(List.of("CAUTION이에요"), false, numbers, null)).isFalse();
    }

    @Test
    void 문장_틀은_상황에_맞는_사실을_고름() {
        // 거슬러타기: 더 걷는 시간 + 빨라지는 시간
        assertThat(ReasonService.templates(fact(StrategyType.BACKTRACK, false, 7, 6,
            Stage.OK, Stage.OK, SeatType.OBSERVED), false))
            .containsExactly("이전 정류장까지 6분 더 걸으면 7분 빨리 도착해요");
        // 늦는 대안
        assertThat(ReasonService.templates(fact(StrategyType.DETOUR, false, -5, 0,
            Stage.OK, Stage.OK, SeatType.OBSERVED), false))
            .containsExactly("지금 경로보다 5분 늦게 도착해요");
        // 추천 다른 교통수단: 좌석·여유 정보가 없으면 기다림 없음
        assertThat(ReasonService.templates(fact(StrategyType.OTHER_MODE, true, 29, 0,
            null, null, null), false))
            .containsExactly("버스 대신 가면 29분 빨리 도착해요", "버스를 기다리지 않아도 돼요");
        // 현재 경로 여유 위험
        assertThat(ReasonService.templates(fact(StrategyType.KEEP, true, 0, 0,
            Stage.RISK, Stage.OK, SeatType.PREDICTED), false))
            .containsExactly("지금 가면 버스를 놓칠 수 있어요", "그래도 다른 방법보다 늦지 않아요");
    }

    @Test
    void 주의_이유는_여유_좌석_정보없음_순으로_정함() {
        assertThat(ReasonService.cautionCause(withBus(Stage.CAUTION, Stage.CAUTION, Stage.OK)))
            .isEqualTo(ReasonService.CautionCause.SLACK);
        assertThat(ReasonService.cautionCause(withBus(Stage.CAUTION, Stage.OK, Stage.CAUTION)))
            .isEqualTo(ReasonService.CautionCause.SEAT);
        assertThat(ReasonService.cautionCause(withBus(Stage.CAUTION, Stage.UNKNOWN, Stage.UNKNOWN)))
            .isEqualTo(ReasonService.CautionCause.NO_INFO);
        assertThat(ReasonService.cautionCause(withBus(Stage.OK, Stage.OK, Stage.OK))).isNull();
    }

    @Test
    void 추천_전략이_주의면_문장_틀_두번째_문장에_이유를_씀() {
        Fact fact = new Fact(StrategyType.BACKTRACK, true, "5003A", null, 70, 5, 3,
            Stage.CAUTION, Stage.CAUTION, null, null, null, 4, null,
            ReasonService.CautionCause.SLACK);

        assertThat(ReasonService.templates(fact, false)).containsExactly(
            "이전 정류장까지 3분 더 걸으면 5분 빨리 도착해요",
            "버스 도착까지 여유가 빠듯해요"
        );
    }

    @Test
    void 다른_전략의_숫자를_쓰면_그_전략만_탈락() {
        Fact alt = new Fact(StrategyType.ALT_ROUTE, true, "5005", null, 66, 10, 0,
            Stage.OK, Stage.OK, null, null, null, 5, null, null);
        Fact keep = new Fact(StrategyType.KEEP, false, "5003A", null, 76, 0, 0,
            Stage.OK, Stage.OK, null, null, null, 7, null, null);

        // 대체 노선 문장에 KEEP 대기 7분을 씀
        var result = service.parseValid("""
            {"ALT_ROUTE": ["7분 뒤 버스보다 10분 빨리 도착해요"],
             "KEEP": ["7분 뒤 버스를 여유 있게 탈 수 있어요"]}
            """, List.of(alt, keep));

        assertThat(result).containsOnlyKeys(StrategyType.KEEP);
    }

    @Test
    void 시간_차이가_작으면_좌석을_먼저_말함() {
        Fact fact = new Fact(StrategyType.BACKTRACK, true, "5003A", null, 75, 1, 3,
            Stage.OK, Stage.OK, Stage.OK, 18, SeatType.PREDICTED, 6, null, null);

        assertThat(ReasonService.templates(fact, false)).containsExactly(
            "빈자리가 넉넉할 것으로 예상돼요",
            "이전 정류장까지 3분 더 걸으면 1분 빨리 도착해요"
        );
    }

    @Test
    void 여유가_위험인데_놓칠_위험을_안_쓰면_탈락() {
        Fact alt = new Fact(StrategyType.BACKTRACK, true, "5003A", null, 62, 14, 6,
            Stage.OK, Stage.OK, null, null, null, 12, null, null);
        Fact keep = new Fact(StrategyType.KEEP, false, "5003A", null, 76, 0, 0,
            Stage.RISK, Stage.RISK, null, null, null, 4, 18,
            ReasonService.CautionCause.SLACK);

        var weak = service.parseValid("""
            {"BACKTRACK": ["이전 정류장까지 걸어가면 14분 빨리 도착해요"],
             "KEEP": ["4분 뒤 버스라 정류장까지 서둘러야 해요"]}
            """, List.of(alt, keep));
        var warned = service.parseValid("""
            {"BACKTRACK": ["이전 정류장까지 걸어가면 14분 빨리 도착해요"],
             "KEEP": ["4분 뒤 버스라 놓치면 18분 더 기다려요"]}
            """, List.of(alt, keep));

        assertThat(weak).containsOnlyKeys(StrategyType.BACKTRACK);
        assertThat(warned).containsKeys(StrategyType.BACKTRACK, StrategyType.KEEP);
    }

    @Test
    void 여유가_위험한_대안은_문장_틀도_놓칠_위험을_씀() {
        Fact risky = new Fact(StrategyType.BACKTRACK, false, "5003A", null, 62, 14, 6,
            Stage.RISK, Stage.RISK, null, null, null, 2, 18,
            ReasonService.CautionCause.SLACK);

        assertThat(ReasonService.templates(risky, false))
            .containsExactly("14분 빨라도 버스를 놓칠 수 있어요");
    }

    @Test
    void 우회를_바꾸면이라고_쓰면_탈락() {
        Fact detour = new Fact(StrategyType.DETOUR, false, "33-2", null, 62, 11, 4,
            Stage.OK, Stage.OK, null, null, null, 5, null, null);
        Fact keep = new Fact(StrategyType.KEEP, true, "5003B", null, 73, 0, 0,
            Stage.OK, Stage.OK, null, null, null, 7, null, null);

        var changed = service.parseValid("""
            {"DETOUR": ["33-2번으로 바꾸면 11분 빨리 도착해요"],
             "KEEP": ["7분 뒤 버스를 여유 있게 탈 수 있어요"]}
            """, List.of(detour, keep));
        var walk = service.parseValid("""
            {"DETOUR": ["다른 정류장에서 타면 11분 빨리 도착해요"],
             "KEEP": ["7분 뒤 버스를 여유 있게 탈 수 있어요"]}
            """, List.of(detour, keep));

        assertThat(changed).containsOnlyKeys(StrategyType.KEEP);
        assertThat(walk).containsKeys(StrategyType.DETOUR, StrategyType.KEEP);
    }

    @Test
    void 좌석_값이_없는데_좌석을_말하면_탈락() {
        // 지하철이 1분 빠른 경우 (실제 E2E: "지하철로 가면 좌석과 여유가 있어요")
        Fact subway = new Fact(StrategyType.OTHER_MODE, true, null, true, 73, 1, 0,
            Stage.OK, null, null, null, null, null, null, null);
        Fact keep = new Fact(StrategyType.KEEP, false, "5005", null, 74, 0, 0,
            Stage.OK, Stage.OK, null, null, null, 8, 4, null);

        var seat = service.parseValid("""
            {"OTHER_MODE": ["지하철로 가면 좌석과 여유가 있어요"],
             "KEEP": ["8분 뒤 버스를 여유 있게 탈 수 있어요"]}
            """, List.of(subway, keep));
        var noSeat = service.parseValid("""
            {"OTHER_MODE": ["버스를 기다리지 않아도 돼요", "지하철로 가면 1분 빨리 도착해요"],
             "KEEP": ["8분 뒤 버스를 여유 있게 탈 수 있어요"]}
            """, List.of(subway, keep));

        assertThat(seat).containsOnlyKeys(StrategyType.KEEP);
        assertThat(noSeat).containsKeys(StrategyType.OTHER_MODE, StrategyType.KEEP);
        // 탈락하면 문장 틀: 기다리지 않는다는 말을 먼저
        assertThat(ReasonService.templates(subway, false))
            .containsExactly("버스를 기다리지 않아도 돼요", "지하철로 가면 1분 빨리 도착해요");
    }

    @Test
    void 앞_정류장_앉아_라는_말은_쓰지_않음() {
        assertThat(ReasonService.valid(List.of("앞 정류장에서 타면 5분 빨라요"), false,
            Set.of("5"), 5)).isFalse();
        assertThat(ReasonService.valid(List.of("5003A번에 앉아 갈 수 있어요"), false,
            Set.of("5003"), null)).isFalse();
    }

    @Test
    void 빠름_늦음을_반대로_쓰면_탈락() {
        Set<String> numbers = Set.of("5", "9");

        assertThat(ReasonService.valid(List.of("9분 빨리 도착해요"), false, numbers, 9)).isTrue();
        assertThat(ReasonService.valid(List.of("9분 늦게 도착해요"), false, numbers, 9)).isFalse();
        assertThat(ReasonService.valid(List.of("5분 빨리 도착해요"), false, numbers, -5)).isFalse();
        assertThat(ReasonService.valid(List.of("5분 더 걷고 늦어요"), false, numbers, 9)).isTrue();
    }

    @Test
    void 지금_경로가_위험한데_1위면_그래도_나은_이유를_붙임() {
        Fact risky = new Fact(StrategyType.KEEP, true, "5003A", null, 70, 0, 0,
            Stage.CAUTION, Stage.CAUTION, null, null, null, 4, 20,
            ReasonService.CautionCause.SLACK);

        assertThat(ReasonService.templates(risky, false)).containsExactly(
            "버스 도착까지 여유가 빠듯해요", "그래도 다른 방법보다 늦지 않아요"
        );
        // 다른 전략이 없으면 놓쳤을 때 기다릴 시간
        assertThat(ReasonService.templates(risky, true)).containsExactly(
            "버스 도착까지 여유가 빠듯해요", "놓치면 20분 더 기다려야 해요"
        );
    }

    @Test
    void 다른_교통수단은_지하철이면_지하철로_씀() {
        StrategyView subway = otherMode("SUBWAY");
        StrategyView walk = otherMode("WALK");

        assertThat(ReasonService.bySubway(subway)).isTrue();
        assertThat(ReasonService.bySubway(walk)).isNull();
        assertThat(ReasonService.templates(ReasonService.fact(subway, 80, NOW), false))
            .containsExactly("지하철로 가면 10분 빨리 도착해요");
        assertThat(ReasonService.templates(ReasonService.fact(walk, 80, NOW), false))
            .containsExactly("버스 대신 가면 10분 빨리 도착해요");
    }

    @Test
    void 사실_JSON에는_정류장_이름을_넣지_않음() {
        StrategyView detour = new StrategyView(
            false, false, StrategyType.DETOUR, "동백역에서 5000번 타기", "동백역",
            127.15, 37.27, 4, Stage.OK, 70, 70, 70, NOW, null, null, null, null,
            null, List.of(), new TripRoute(List.of()), List.of()
        );

        assertThat(service.factsJson(List.of(ReasonService.fact(detour, 80, NOW))))
            .doesNotContain("동백역", "moveToName");
    }

    // 도보 → (mode) → 버스 경로의 KEEP. 버스는 11분 뒤 도착
    private static StrategyView keepWith(String firstMode) {
        StrategyView keep = keep(true, 80, Stage.OK);
        List<TripSegment> segments = List.of(
            new TripSegment(0, 1, "WALK", null, null, null, null, "출발지", "역", null,
                NOW, NOW, null, null),
            new TripSegment(1, 2, firstMode, null, "5003A", null, null, "역", "동백역",
                null, NOW, NOW, null, null),
            new TripSegment(2, 3, "BUS", null, "5003A", null, null, "동백역", "강남역",
                null, NOW, NOW, null, null)
        );

        return new StrategyView(
            keep.recommended(), keep.isCurrent(), keep.strategyType(), keep.title(),
            keep.moveToName(), keep.moveToX(), keep.moveToY(), keep.extraWalkMin(),
            keep.strategyRiskStage(), keep.totalMinIfBoard(), keep.totalMinIfMiss(),
            keep.comparisonValueMin(), keep.expectedArrivalAt(), keep.remainSeats(),
            keep.seatType(), keep.busArrivalAt(), keep.nextBusArrivalAt(),
            keep.missIntervalMin(), keep.reasons(), new TripRoute(segments), keep.analysis()
        );
    }

    private static StrategyView otherMode(String mode) {
        TripSegment segment = new TripSegment(
            0, 1, mode, null, null, null, null, "기흥역", "강남역", null,
            NOW, NOW, null, null
        );

        return new StrategyView(
            false, false, StrategyType.OTHER_MODE, "제목", null, null, null, 0,
            Stage.OK, 70, 70, 70, NOW, null, null, null, null, null,
            List.of(), new TripRoute(List.of(segment)), List.of()
        );
    }

    @Test
    void 노선_이름은_괄호_설명을_뺌() {
        assertThat(ReasonService.shortLabel("5001-1(예약.평일운행)")).isEqualTo("5001-1");
        assertThat(ReasonService.shortLabel("5003A")).isEqualTo("5003A");
    }

    @Test
    void 사실_JSON에는_위치_좌표를_넣지_않음() {
        String json = service.factsJson(List.of(
            ReasonService.fact(altRoute(true, 71), 80, NOW)
        ));

        assertThat(json).contains("\"diffMin\":9", "\"waitMin\":11", "\"routeLabel\":\"5003A\"");
        assertThat(json).doesNotContain("moveToX", "127.", "null");
    }

    private void givenLlm(String answer) {
        when(openAi.completeJson(eq(ReasonService.INSTRUCTIONS), anyString(), any()))
            .thenReturn(Optional.of(answer));
    }

    private static Fact fact(
        StrategyType type, boolean recommended, Integer diff, int extraWalk,
        Stage slack, Stage seat, SeatType seatType
    ) {
        return new Fact(type, recommended, "5003A", null, 70, diff, extraWalk,
            Stage.OK, slack, seat, 10, seatType, 5, 15, null);
    }

    private static StrategyView withBus(Stage stage, Stage slack, Stage seat) {
        SegmentView segment = new SegmentView(
            1, "5003A", "동백역", NOW, 3, slack, 2, SeatType.PREDICTED,
            seat, stage, null, 60, null, null, null, null, NOW
        );

        return new StrategyView(
            false, true, StrategyType.KEEP, "제목", null, null, null, 0,
            stage, 60, 60, 60, NOW, 2, SeatType.PREDICTED, NOW, null, null,
            List.of(), new TripRoute(List.of()), List.of(segment)
        );
    }

    private static StrategyView altRoute(boolean recommended, int value) {
        return view(StrategyType.ALT_ROUTE, recommended, false, value, Stage.OK, Stage.OK);
    }

    private static StrategyView keep(boolean recommended, int value, Stage seatStage) {
        return view(StrategyType.KEEP, recommended, true, value,
            seatStage == Stage.OK ? Stage.OK : Stage.CAUTION, seatStage);
    }

    private static StrategyView view(
        StrategyType type, boolean recommended, boolean current, int value,
        Stage stage, Stage seatStage
    ) {
        OffsetDateTime bus = NOW.plusMinutes(11);
        SegmentView segment = new SegmentView(
            1, "5003A", "동백역", bus, 6, Stage.OK, 12, SeatType.PREDICTED,
            seatStage, stage, null, value, null, null, null, null, NOW
        );

        return new StrategyView(
            recommended, current, type, "제목", null, 127.15, 37.27, 0,
            stage, value, value, value, NOW.plusMinutes(value), 12,
            SeatType.PREDICTED, bus, null, null, List.of(),
            new TripRoute(List.of()), List.of(segment)
        );
    }
}
