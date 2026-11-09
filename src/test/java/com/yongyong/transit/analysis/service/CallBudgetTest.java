package com.yongyong.transit.analysis.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CallBudgetTest {

    @Test
    void 한도까지만_허용하고_넘으면_한도_도달() {
        CallBudget budget = CallBudget.of(2);

        assertThat(budget.hasRemaining()).isTrue();
        budget.use();
        assertThat(budget.hasRemaining()).isTrue();
        budget.use();

        assertThat(budget.limitReached()).isFalse();
        assertThat(budget.hasRemaining()).isFalse();
        assertThat(budget.limitReached()).isTrue();
        assertThat(budget.used()).isEqualTo(2);
    }

    @Test
    void none은_처음부터_호출_불가() {
        assertThat(CallBudget.none().hasRemaining()).isFalse();
    }
}
