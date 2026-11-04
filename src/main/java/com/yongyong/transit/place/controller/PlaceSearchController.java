
package com.yongyong.transit.place.controller;

import com.yongyong.transit.place.service.PlaceSearchService;
import com.yongyong.transit.place.service.PlaceSearchService.PlaceSearchResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/places")
public class PlaceSearchController {

    private final PlaceSearchService placeSearchService;

    public PlaceSearchController(
        PlaceSearchService placeSearchService
    ) {
        this.placeSearchService = placeSearchService;
    }

    @GetMapping("/search")
    public PlaceSearchResponse searchPlaces(
        @RequestParam String query,
        @RequestParam(required = false) Double x,
        @RequestParam(required = false) Double y,
        @RequestParam(required = false) Integer radius
    ) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "검색어를 입력해야 합니다."
            );
        }

        String keyword = query.trim();

        if (keyword.length() > 100) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "검색어가 너무 깁니다."
            );
        }

        if ((x == null) != (y == null)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "중심 좌표 X와 Y를 함께 입력해야 합니다."
            );
        }

        if (x != null && (
            !Double.isFinite(x)
                || !Double.isFinite(y)
                || x < -180 || x > 180
                || y < -90 || y > 90
        )) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "유효하지 않은 중심 좌표입니다."
            );
        }

        if (radius != null) {
            if (x == null || radius < 0 || radius > 20000) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "검색 반경은 중심 좌표와 함께 0~20000m로 지정해야 합니다."
                );
            }
        }

        return placeSearchService.search(
            keyword, x, y, radius
        );
    }
}
