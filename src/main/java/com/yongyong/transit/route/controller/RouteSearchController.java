
package com.yongyong.transit.route.controller;

import com.yongyong.transit.route.service.RouteSearchService;
import com.yongyong.transit.route.service.RouteSearchService.RouteSearchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Tag(name = "1. 장소·경로 탐색")
@RestController
@RequestMapping("/api/v1/routes")
public class RouteSearchController {

    private final RouteSearchService routeSearchService;

    public RouteSearchController(
            RouteSearchService routeSearchService
    ) {
        this.routeSearchService = routeSearchService;
    }

    @Operation(summary = "대중교통 경로 탐색")
    @GetMapping("/search")
    public RouteSearchResponse searchRoutes(
            @RequestParam double startX,
            @RequestParam double startY,
            @RequestParam double endX,
            @RequestParam double endY
    ) {
        validateCoordinates(startX, startY);
        validateCoordinates(endX, endY);

        if (startX == endX && startY == endY) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "출발지와 도착지가 동일합니다."
            );
        }

        return routeSearchService.search(
                startX,
                startY,
                endX,
                endY
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
