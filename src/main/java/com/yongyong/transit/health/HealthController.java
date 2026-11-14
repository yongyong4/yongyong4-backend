package com.yongyong.transit.health;

import java.time.Instant;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "내부·개발용")
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @Operation(summary = "서버 상태 확인")
    @GetMapping
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "yongyong-be",
                "timestamp", Instant.now().toString()
        );
    }
}
