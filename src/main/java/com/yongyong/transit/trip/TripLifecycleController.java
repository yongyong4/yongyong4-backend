
package com.yongyong.transit.trip;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/trips")
public class TripLifecycleController {

    private final TripLifecycleService tripLifecycleService;

    public TripLifecycleController(TripLifecycleService tripLifecycleService) {
        this.tripLifecycleService = tripLifecycleService;
    }

    @PatchMapping("/{tripId}/end")
    public ResponseEntity<Map<String, Object>> endTrip(
        @RequestHeader("X-Device-Id") String deviceId,
        @PathVariable long tripId,
        @RequestBody JsonNode request
    ) {
        return ResponseEntity.ok(
            tripLifecycleService.endTrip(deviceId, tripId, request)
        );
    }

    @GetMapping("/{tripId}/summary")
    public ResponseEntity<Map<String, Object>> getSummary(
        @RequestHeader("X-Device-Id") String deviceId,
        @PathVariable long tripId
    ) {
        return ResponseEntity.ok(
            tripLifecycleService.getSummary(deviceId, tripId)
        );
    }

    @PatchMapping("/{tripId}/summary/seen")
    public ResponseEntity<Map<String, Object>> markSummarySeen(
        @RequestHeader("X-Device-Id") String deviceId,
        @PathVariable long tripId,
        @RequestBody JsonNode request
    ) {
        return ResponseEntity.ok(
            tripLifecycleService.markSummarySeen(deviceId, tripId, request)
        );
    }
}
