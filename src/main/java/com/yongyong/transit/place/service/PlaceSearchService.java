
package com.yongyong.transit.place.service;

import com.yongyong.transit.place.client.KakaoPlaceClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

@Service
public class PlaceSearchService {

    private final KakaoPlaceClient kakaoPlaceClient;
    private final JsonMapper jsonMapper;

    public PlaceSearchService(
        KakaoPlaceClient kakaoPlaceClient,
        JsonMapper jsonMapper
    ) {
        this.kakaoPlaceClient = kakaoPlaceClient;
        this.jsonMapper = jsonMapper;
    }

    public PlaceSearchResponse search(
        String query,
        Double x,
        Double y,
        Integer radius
    ) {
        String responseBody = kakaoPlaceClient.searchPlaces(
            query, x, y, radius
        );

        JsonNode root;

        try {
            root = jsonMapper.readTree(responseBody);
        } catch (JacksonException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Kakao 응답을 분석할 수 없습니다.",
                e
            );
        }

        if (root == null || !root.isObject()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Kakao 응답 형식이 올바르지 않습니다."
            );
        }

        JsonNode documents = root.path("documents");

        if (!documents.isArray()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Kakao 장소 검색 결과가 올바르지 않습니다."
            );
        }

        List<PlaceInfo> places = new ArrayList<>();

        for (JsonNode document : documents) {
            places.add(new PlaceInfo(
                getText(document, "id"),
                getText(document, "place_name"),
                getText(document, "address_name"),
                getText(document, "road_address_name"),
                getCoordinate(document, "x"),
                getCoordinate(document, "y"),
                getInteger(document, "distance"),
                getText(document, "place_url")
            ));
        }

        return new PlaceSearchResponse(
            query,
            places.size(),
            places
        );
    }

    private static String getText(
        JsonNode parent,
        String field
    ) {
        JsonNode node = parent.path(field);

        if (node.isMissingNode() || node.isNull()) {
            return null;
        }

        return node.asString();
    }

    private static Double getCoordinate(
        JsonNode parent,
        String field
    ) {
        String value = getText(parent, field);

        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "장소 좌표가 누락되었습니다."
            );
        }

        try {
            double coordinate = Double.parseDouble(value);

            if (!Double.isFinite(coordinate)) {
                throw new NumberFormatException();
            }

            return coordinate;

        } catch (NumberFormatException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "장소 좌표 형식이 올바르지 않습니다.",
                e
            );
        }
    }

    private static Integer getInteger(
        JsonNode parent,
        String field
    ) {
        String value = getText(parent, field);

        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record PlaceSearchResponse(
        String query,
        int count,
        List<PlaceInfo> places
    ) {
    }

    public record PlaceInfo(
        String placeId,
        String placeName,
        String addressName,
        String roadAddressName,
        Double x,
        Double y,
        Integer distanceMeters,
        String placeUrl
    ) {
    }
}
