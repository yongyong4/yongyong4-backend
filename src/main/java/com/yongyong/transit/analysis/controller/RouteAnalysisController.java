package com.yongyong.transit.analysis.controller;

import com.yongyong.transit.analysis.service.RouteAnalysisService;
import com.yongyong.transit.analysis.service.RouteAnalysisService.AnalysisRequest;
import com.yongyong.transit.analysis.service.RouteAnalysisService.AnalysisResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "2. 실시간 버스 정보 및 위험 분석")
@RestController
@RequestMapping("/api/v1/routes")
public class RouteAnalysisController {

    private final RouteAnalysisService routeAnalysisService;

    public RouteAnalysisController(
        RouteAnalysisService routeAnalysisService
    ) {
        this.routeAnalysisService = routeAnalysisService;
    }

    @Operation(summary = "후보 경로 위험 분석")
    @PostMapping("/analysis")
    public AnalysisResponse analyze(
        @RequestBody AnalysisRequest request
    ) {
        return routeAnalysisService.analyze(request);
    }
}
