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
            "자리도 넉넉할 것으로 예상돼요"
        );
    }

    @Test
    void 재평가는_LLM을_부르지_않음() {
        List<StrategyView> result = service.withReasons(
            List.of(keep(true, 80, Stage.OK)), Mode.REEVALUATION, NOW
        );

        assertThat(result.get(0).reasons()).containsExactly(
            "지금 경로로 여유 있게 탈 수 있어요",
            "자리도 넉넉할 것으로 예상돼요"
        );
        verify(openAi, never()).completeJson(anyString(), anyString(), any());
    }

    @Test
    void 문장_검사는_개수_40자_해요체_확률_숫자를_봄() {
        Set<String> numbers = Set.of("9", "5003");

        assertThat(ReasonService.valid(List.of("9분 빨리 도착해요."), false, numbers)).isTrue();
        assertThat(ReasonService.valid(List.of(), false, numbers)).isFalse();
        assertThat(ReasonService.valid(List.of("a요", "b요"), false, numbers)).isFalse();
        assertThat(ReasonService.valid(List.of("9분 빨리 도착합니다"), false, numbers)).isFalse();
        assertThat(ReasonService.valid(List.of("탈 확률이 높아요"), false, numbers)).isFalse();
        assertThat(ReasonService.valid(List.of("10분 빨라요"), false, numbers)).isFalse();
        assertThat(ReasonService.valid(List.of("가".repeat(40) + "요"), false, numbers)).isFalse();
    }

    @Test
    void 문장_틀은_상황에_맞는_사실을_고름() {
        // 거슬러타기: 더 걷는 시간 + 빨라지는 시간
        assertThat(ReasonService.templates(fact(StrategyType.BACKTRACK, false, 7, 6,
            Stage.OK, Stage.OK, SeatType.OBSERVED)))
            .containsExactly("앞 정류장까지 6분 더 걸으면 7분 빨리 도착해요");
        // 늦는 대안
        assertThat(ReasonService.templates(fact(StrategyType.DETOUR, false, -5, 0,
            Stage.OK, Stage.OK, SeatType.OBSERVED)))
            .containsExactly("지금 경로보다 5분 늦게 도착해요");
        // 추천 다른 교통수단: 좌석·여유 정보가 없으면 기다림 없음
        assertThat(ReasonService.templates(fact(StrategyType.OTHER_MODE, true, 29, 0,
            null, null, null)))
            .containsExactly("버스 대신 가면 29분 빨리 도착해요", "버스를 기다리지 않아도 돼요");
        // 현재 경로 여유 위험
        assertThat(ReasonService.templates(fact(StrategyType.KEEP, true, 0, 0,
            Stage.RISK, Stage.OK, SeatType.PREDICTED)))
            .containsExactly("지금 가면 버스를 놓칠 수 있어요");
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
        return new Fact(type, recommended, "제목", "5003A", null, 70, diff, extraWalk,
            Stage.OK, slack, seat, 10, seatType, 5, 15);
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
