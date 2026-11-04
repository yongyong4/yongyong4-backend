
package com.yongyong.transit.route.walking.controller;

import com.yongyong.transit.route.walking.service.WalkingRouteService;
import com.yongyong.transit.route.walking.service.WalkingRouteService.WalkingRouteResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/routes")
public class WalkingRouteController {

    private static final Set<String> ALLOWED_MODES = Set.of(
        "SHORTEST",
        "BROAD_FIRST",
        "ACCESSIBLE"
    );

    private final WalkingRouteService walkingRouteService;

    public WalkingRouteController(
        WalkingRouteService walkingRouteService
    ) {
        this.walkingRouteService = walkingRouteService;
    }

    @GetMapping("/walking")
    public WalkingRouteResponse searchWalkingRoute(
        @RequestParam double startX,
        @RequestParam double startY,
        @RequestParam double endX,
        @RequestParam double endY,
        @RequestParam(defaultValue = "SHORTEST")
        String routeMode
    ) {
        validateCoordinates(startX, startY);
        validateCoordinates(endX, endY);

        if (startX == endX && startY == endY) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "출발지와 도착지가 동일합니다."
            );
        }

        String normalizedMode =
            routeMode.trim().toUpperCase(Locale.ROOT);

        if (!ALLOWED_MODES.contains(normalizedMode)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "지원하지 않는 도보 경로 탐색 옵션입니다."
            );
        }

        return walkingRouteService.search(
            startX,
            startY,
            endX,
            endY,
            normalizedMode
        );
    }

    private void validateCoordinates(
        double longitude,
        double latitude
    ) {
        if (!Double.isFinite(longitude)
            || !Double.isFinite(latitude)
            || longitude < -180
            || longitude > 180
            || latitude < -90
            || latitude > 90) {

            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "유효하지 않은 좌표입니다."
            );
        }
    }
}
