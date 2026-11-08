package com.yongyong.transit.strategy.service;

import com.yongyong.transit.analysis.service.SegmentAnalyzer.Stage;
import com.yongyong.transit.strategy.service.StrategyService.StrategyType;
import com.yongyong.transit.strategy.service.StrategyService.StrategyView;
import com.yongyong.transit.strategy.service.StrategyService.TripRoute;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyRankingTest {

    @Test
    void 비교값은_가능이면_탔을_때_주의_위험이면_놓쳤을_때() {
        assertThat(StrategyService.comparisonValueMin(Stage.OK, 58, 76)).isEqualTo(58);
        assertThat(StrategyService.comparisonValueMin(Stage.CAUTION, 58, 76)).isEqualTo(76);
        assertThat(StrategyService.comparisonValueMin(Stage.RISK, 58, 76)).isEqualTo(76);
    }

    @Test
    void 다음_버스_정보가_없으면_탔을_때_시간을_비교값으로() {
        assertThat(StrategyService.comparisonValueMin(Stage.RISK, 58, null)).isEqualTo(58);
    }

    @Test
    void 명세_예시_순서와_1위_추천() {
        List<StrategyView> ranked = StrategyService.rank(List.of(
            view(StrategyType.KEEP, 76, 58, 0),
            view(StrategyType.ALT_ROUTE, 71, 71, 0),
            view(StrategyType.BACKTRACK, 69, 69, 6)
        ));

        assertThat(ranked).extracting(StrategyView::strategyType).containsExactly(
            StrategyType.BACKTRACK, StrategyType.ALT_ROUTE, StrategyType.KEEP
        );
        assertThat(ranked).extracting(StrategyView::recommended)
            .containsExactly(true, false, false);
    }

    @Test
    void 비교값이_같으면_탔을_때_시간_추가_도보_KEEP_순() {
        List<StrategyView> byBoard = StrategyService.rank(List.of(
            view(StrategyType.ALT_ROUTE, 70, 65, 0),
            view(StrategyType.DETOUR, 70, 60, 0)
        ));
        assertThat(byBoard.get(0).strategyType()).isEqualTo(StrategyType.DETOUR);

        List<StrategyView> byWalk = StrategyService.rank(List.of(
            view(StrategyType.BACKTRACK, 70, 60, 5),
            view(StrategyType.DETOUR, 70, 60, 2)
        ));
        assertThat(byWalk.get(0).strategyType()).isEqualTo(StrategyType.DETOUR);

        List<StrategyView> keepFirst = StrategyService.rank(List.of(
            view(StrategyType.ALT_ROUTE, 70, 60, 0),
            view(StrategyType.KEEP, 70, 60, 0)
        ));
        assertThat(keepFirst.get(0).strategyType()).isEqualTo(StrategyType.KEEP);
        assertThat(keepFirst.get(0).recommended()).isTrue();
    }

    @Test
    void 종류마다_가장_좋은_1개만_남기고_KEEP은_꼴찌여도_포함() {
        List<StrategyView> ranked = StrategyService.rank(List.of(
            view(StrategyType.KEEP, 90, 60, 0),
            view(StrategyType.BACKTRACK, 73, 73, 8),
            view(StrategyType.BACKTRACK, 69, 69, 6),
            view(StrategyType.ALT_ROUTE, 80, 80, 0)
        ));

        assertThat(ranked).extracting(StrategyView::strategyType).containsExactly(
            StrategyType.BACKTRACK, StrategyType.ALT_ROUTE, StrategyType.KEEP
        );
        assertThat(ranked.get(0).comparisonValueMin()).isEqualTo(69);
    }

    @Test
    void 대안이_없으면_KEEP이_추천() {
        List<StrategyView> ranked = StrategyService.rank(List.of(
            view(StrategyType.KEEP, 90, 60, 0)
        ));

        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).recommended()).isTrue();
    }

    private static StrategyView view(
        StrategyType type,
        int comparisonValueMin,
        int totalMinIfBoard,
        int extraWalkMin
    ) {
        return new StrategyView(
            false, type == StrategyType.KEEP, type, type.name(),
            null, null, null, extraWalkMin, Stage.OK,
            totalMinIfBoard, null, comparisonValueMin,
            null, null, null, null, null, null, List.of(),
            new TripRoute(List.of()), List.of()
        );
    }
}
