
package com.yongyong.transit.collector.controller;

import com.yongyong.transit.collector.service.CollectorQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Tag(name = "내부·개발용")
@RestController
@RequestMapping("/api/v1/collector")
public class CollectorController {

    private final CollectorQueryService collectorQueryService;

    public CollectorController(
        CollectorQueryService collectorQueryService
    ) {
        this.collectorQueryService = collectorQueryService;
    }

    @Operation(summary = "수집 데이터 스냅샷 조회")
    @GetMapping("/snapshot")
    public CollectorQueryService.SegmentSnapshot getSnapshot(
        @RequestParam String routeId,
        @RequestParam String stationId
    ) {
        if (!routeId.matches("\\d+")
            || !stationId.matches("\\d+")) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "routeId and stationId must be numeric"
            );
        }

        return collectorQueryService.getSegmentSnapshot(
            routeId,
            stationId
        );
    }
}
