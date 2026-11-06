
package com.yongyong.transit.trip;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.Map;

@Tag(name = "6. 이동 종료 및 기록")
@RestController
@RequestMapping("/api/v1/trips")
public class TripLifecycleController {

    private final TripLifecycleService tripLifecycleService;

    public TripLifecycleController(TripLifecycleService tripLifecycleService) {
        this.tripLifecycleService = tripLifecycleService;
    }

    @Operation(summary = "이용자 이동 종료 처리")
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

    @Operation(summary = "이동 요약 조회")
    @GetMapping("/{tripId}/summary")
    public ResponseEntity<Map<String, Object>> getSummary(
        @RequestHeader("X-Device-Id") String deviceId,
        @PathVariable long tripId
    ) {
        return ResponseEntity.ok(
            tripLifecycleService.getSummary(deviceId, tripId)
        );
    }

    @Operation(summary = "이동 요약 확인 완료")
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
