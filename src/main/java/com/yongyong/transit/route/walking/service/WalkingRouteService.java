
package com.yongyong.transit.route.walking.service;

import com.yongyong.transit.route.walking.client.KakaoWalkingClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

@Service
public class WalkingRouteService {

    private final KakaoWalkingClient kakaoWalkingClient;
    private final JsonMapper jsonMapper;

    public WalkingRouteService(
        KakaoWalkingClient kakaoWalkingClient,
        JsonMapper jsonMapper
    ) {
        this.kakaoWalkingClient = kakaoWalkingClient;
        this.jsonMapper = jsonMapper;
    }

    public WalkingRouteResponse search(
        double startX,
        double startY,
        double endX,
        double endY,
        String routeMode
    ) {
        String responseBody = kakaoWalkingClient.searchWalkingRoute(
            startX,
            startY,
            endX,
            endY,
            routeMode
        );

        JsonNode root;

        try {
            root = jsonMapper.readTree(responseBody);
        } catch (JacksonException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Kakao 도보 경로 응답을 분석할 수 없습니다.",
                e
            );
        }

        if (root == null || !root.isObject()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Kakao 도보 경로 응답 형식이 올바르지 않습니다."
            );
        }

        String status = root.path("status").asString();

        if (!"OK".equals(status)) {
            handleRouteError(status);
        }

        JsonNode route = root.path("route");
        JsonNode properties = route.path("properties");
        JsonNode legs = route.path("legs");

        if (!legs.isArray()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Kakao 도보 경로 구간 정보가 없습니다."
            );
        }

        int totalDistanceMeters = requiredInt(
            properties, "totalDistance"
        );

        int totalTimeSeconds = requiredInt(
            properties, "totalTime"
        );

        List<WalkingStep> steps = new ArrayList<>();
        List<List<Double>> allPoints = new ArrayList<>();

        int stepIndex = 1;

        for (JsonNode leg : legs) {
            JsonNode legSteps = leg.path("steps");

            if (!legSteps.isArray()) {
                continue;
            }

            for (JsonNode step : legSteps) {
                JsonNode stepProperties =
                    step.path("properties");

                JsonNode points =
                    step.path("path").path("points");

                List<List<Double>> stepPoints =
                    new ArrayList<>();

                if (points.isArray()) {
                    for (JsonNode point : points) {
                        if (!point.isArray()
                            || point.size() != 2
                            || !point.get(0).isNumber()
                            || !point.get(1).isNumber()) {
                            throw new ResponseStatusException(
                                HttpStatus.BAD_GATEWAY,
                                "Kakao 경로 좌표 형식이 올바르지 않습니다."
                            );
                        }

                        double x = point.get(0).asDouble();
                        double y = point.get(1).asDouble();

                        if (!Double.isFinite(x)
                            || !Double.isFinite(y)) {
                            throw new ResponseStatusException(
                                HttpStatus.BAD_GATEWAY,
                                "Kakao 경로 좌표가 유효하지 않습니다."
                            );
                        }

                        List<Double> coordinate = List.of(x, y);

                        stepPoints.add(coordinate);

                        // 연속된 중복 좌표만 제거
                        if (allPoints.isEmpty()
                            || !allPoints.get(
                            allPoints.size() - 1
                        ).equals(coordinate)) {
                            allPoints.add(coordinate);
                        }
                    }
                }

                steps.add(new WalkingStep(
                    stepIndex++,
                    getText(stepProperties, "guidance"),
                    getOptionalInt(stepProperties, "distance"),
                    getOptionalInt(stepProperties, "time"),
                    stepPoints
                ));
            }
        }

        if (allPoints.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "지도에 표시할 도보 경로 좌표가 없습니다."
            );
        }

        return new WalkingRouteResponse(
            "KAKAO",
            routeMode,
            totalDistanceMeters,
            totalTimeSeconds,
            steps,
            allPoints
        );
    }

    private void handleRouteError(String status) {
        if ("SAME_POINT".equals(status)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "출발지와 도착지가 동일합니다."
            );
        }

        if ("TOO_FAR_AWAY".equals(status)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "도보 경로를 탐색하기에 거리가 너무 멉니다."
            );
        }

        if ("START_LINK_NOT_FOUND".equals(status)
            || "END_LINK_NOT_FOUND".equals(status)
            || "ROUTE_RESULT_NOT_FOUND".equals(status)) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "도보 경로를 찾을 수 없습니다."
            );
        }

        throw new ResponseStatusException(
            HttpStatus.BAD_GATEWAY,
            "Kakao 도보 경로 탐색 실패: " + status
        );
    }

    private static int requiredInt(
        JsonNode parent,
        String field
    ) {
        JsonNode node = parent.path(field);

        if (!node.isNumber()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Kakao 경로 필수 정보가 누락되었습니다: " + field
            );
        }

        return node.asInt();
    }

    private static Integer getOptionalInt(
        JsonNode parent,
        String field
    ) {
        JsonNode node = parent.path(field);

        if (node.isMissingNode() || node.isNull()) {
            return null;
        }

        return node.asInt();
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

    public record WalkingRouteResponse(
        String provider,
        String routeMode,
        int totalDistanceMeters,
        int totalTimeSeconds,
        List<WalkingStep> steps,
        List<List<Double>> pathPoints
    ) {
    }

    public record WalkingStep(
        int stepIndex,
        String guidance,
        Integer distanceMeters,
        Integer durationSeconds,
        List<List<Double>> pathPoints
    ) {
    }
}
