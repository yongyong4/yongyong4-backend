package com.yongyong.transit.trip;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/trips")
public class TripController {

    private final TripService tripService;

    public TripController(TripService tripService) {
        this.tripService = tripService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createTrip(
        @RequestHeader("X-Device-Id") String deviceId,
        @RequestBody JsonNode request
    ) {
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(tripService.createTrip(deviceId, request));
    }

    @GetMapping("/active")
    public Map<String, Object> getActiveTrip(
        @RequestHeader("X-Device-Id") String deviceId
    ) {
        return tripService.getActiveTrip(deviceId);
    }

    @PatchMapping("/{tripId}/status")
    public Map<String, Object> changeStatus(
        @RequestHeader("X-Device-Id") String deviceId,
        @PathVariable long tripId,
        @RequestBody JsonNode request
    ) {
        return tripService.changeStatus(deviceId, tripId, request);
    }
}
