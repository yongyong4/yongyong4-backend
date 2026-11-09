
package com.yongyong.transit.trip;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.Map;

@Tag(name = "5. 실시간 재평가 및 경로 변경")
@RestController
@RequestMapping("/api/v1/trips")
public class TripReevaluationController {

    private final TripReevaluationService service;

    public TripReevaluationController(TripReevaluationService service) {
        this.service = service;
    }

    @Operation(summary = "남은 이동 경로 재평가 (AUTO/REFRESH/STATUS_CHANGE/RESUME)")
    @PostMapping("/{tripId}/reevaluations")
    public Map<String, Object> reevaluate(
        @RequestHeader("X-Device-Id") String deviceId,
        @PathVariable long tripId,
        @RequestBody JsonNode request
    ) {
        return service.reevaluate(deviceId, tripId, request);
    }

    @Operation(summary = "탑승 실패 후 다음 버스 기다리기")
    @PostMapping("/{tripId}/boarding-failure/wait")
    public Map<String, Object> waitForNextBus(
        @RequestHeader("X-Device-Id") String deviceId,
        @PathVariable long tripId
    ) {
        return service.waitForNextBus(deviceId, tripId);
    }
}
