package com.yongyong.transit.trip;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.Map;

@Tag(name = "4. 이동 세션 관리")
@RestController
@RequestMapping("/api/v1/trips")
public class TripController {

    private final TripService tripService;

    public TripController(TripService tripService) {
        this.tripService = tripService;
    }

    @Operation(summary = "이동 세션 생성 및 경로 저장")
    @PostMapping
    public ResponseEntity<Map<String, Object>> createTrip(
        @RequestHeader("X-Device-Id") String deviceId,
        @RequestBody JsonNode request
    ) {
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(tripService.createTrip(deviceId, request));
    }

    @Operation(summary = "진행 중인 이동 조회·이어가기")
    @GetMapping("/active")
    public Map<String, Object> getActiveTrip(
        @RequestHeader("X-Device-Id") String deviceId
    ) {
        return tripService.getActiveTrip(deviceId);
    }

    @Operation(summary = "이동 상태 변경 및 기록")
    @PatchMapping("/{tripId}/status")
    public Map<String, Object> changeStatus(
        @RequestHeader("X-Device-Id") String deviceId,
        @PathVariable long tripId,
        @RequestBody JsonNode request
    ) {
        return tripService.changeStatus(deviceId, tripId, request);
    }
}
