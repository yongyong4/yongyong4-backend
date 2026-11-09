package com.yongyong.transit.strategy.controller;

import com.yongyong.transit.strategy.service.StrategyService;
import com.yongyong.transit.strategy.service.StrategyService.StrategyRequest;
import com.yongyong.transit.strategy.service.StrategyService.StrategyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "3. 대안 전략 생성·비교·추천")
@RestController
@RequestMapping("/api/v1/strategies")
public class StrategyController {

    private final StrategyService strategyService;

    public StrategyController(
        StrategyService strategyService
    ) {
        this.strategyService = strategyService;
    }

    @Operation(summary = "전략 비교·추천")
    @PostMapping
    public StrategyResponse strategies(
        @RequestBody StrategyRequest request
    ) {
        return strategyService.strategies(request);
    }
}
