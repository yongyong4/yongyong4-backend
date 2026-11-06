
package com.yongyong.transit.trip;

import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/trips")
public class TripDecisionController {

    private final TripDecisionService tripDecisionService;

    public TripDecisionController(TripDecisionService tripDecisionService) {
        this.tripDecisionService = tripDecisionService;
    }

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
