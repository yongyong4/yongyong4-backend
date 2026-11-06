
package com.yongyong.transit.trip;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.Map;

@Tag(name = "5. 실시간 재평가 및 경로 변경")
@RestController
@RequestMapping("/api/v1/trips")
public class TripDecisionController {

    private final TripDecisionService tripDecisionService;

    public TripDecisionController(TripDecisionService tripDecisionService) {
        this.tripDecisionService = tripDecisionService;
    }

    @Operation(summary = "경로 변경 제안 수락·무시")
    @PatchMapping("/{tripId}/evaluations/{evaluationId}/decision")
    public Map<String, Object> decide(
        @RequestHeader("X-Device-Id") String deviceId,
        @PathVariable long tripId,
        @PathVariable long evaluationId,
        @RequestBody JsonNode request
    ) {
        return tripDecisionService.decide(
            deviceId, tripId, evaluationId, request
        );
    }
}
