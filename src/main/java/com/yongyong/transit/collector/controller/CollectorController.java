
package com.yongyong.transit.collector.controller;

import com.yongyong.transit.collector.service.CollectorQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/collector")
public class CollectorController {

    private final CollectorQueryService collectorQueryService;

    public CollectorController(
        CollectorQueryService collectorQueryService
    ) {
        this.collectorQueryService = collectorQueryService;
    }

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
