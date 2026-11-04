
package com.yongyong.transit.route.service;

import com.yongyong.transit.route.client.OdsayClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

@Service
public class RouteSearchService {

    private final OdsayClient odsayClient;
    private final JsonMapper jsonMapper;

    public RouteSearchService(
            OdsayClient odsayClient,
            JsonMapper jsonMapper
    ) {
        this.odsayClient = odsayClient;
        this.jsonMapper = jsonMapper;
    }

    public RouteSearchResponse search(
            double startX,
            double startY,
            double endX,
            double endY
    ) {
        String responseBody = odsayClient.searchRoutes(
                startX, startY, endX, endY
        );

        JsonNode root;

        try {
            root = jsonMapper.readTree(responseBody);
        } catch (JacksonException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "ODsay 응답을 분석할 수 없습니다.",
                    e
            );
        }

        if (root == null || !root.isObject()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "ODsay 응답 형식이 올바르지 않습니다."
            );
        }

        JsonNode error = root.path("error");

        if (!error.isMissingNode() && !error.isNull()) {
            int errorCode = error.path("code").asInt();

            if (errorCode == -99) {
                return new RouteSearchResponse(
                        "ODsay",
                        0,
                        List.of()
                );
            }

            if (errorCode == -98) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "출발지와 도착지가 너무 가깝습니다."
                );
            }

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "ODsay 경로 탐색 오류: " + errorCode
            );
        }

        JsonNode paths = root.path("result").path("path");

        if (!paths.isArray()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "ODsay 경로 데이터가 올바르지 않습니다."
            );
        }

        List<RouteInfo> routes = new ArrayList<>();

        int routeIndex = 1;

        for (JsonNode path : paths) {
            JsonNode info = path.path("info");
            JsonNode subPaths = path.path("subPath");

            List<SegmentInfo> segments = new ArrayList<>();

            int segmentIndex = 1;

            if (subPaths.isArray()) {
                for (JsonNode subPath : subPaths) {
                    int trafficType =
                            subPath.path("trafficType").asInt();

                    String mode = switch (trafficType) {
                        case 1 -> "SUBWAY";
                        case 2 -> "BUS";
                        case 3 -> "WALK";
                        default -> "OTHER";
                    };

                    List<BusOption> busOptions =
                            new ArrayList<>();

                    if (trafficType == 2) {
                        JsonNode lanes = subPath.path("lane");

                        if (lanes.isArray()) {
                            for (JsonNode lane : lanes) {
                                busOptions.add(new BusOption(
                                        getText(lane, "busNo"),
                                        getText(lane, "busLocalBlID")
                                ));
                            }
                        }
                    }

                    segments.add(new SegmentInfo(
                            segmentIndex++,
                            mode,
                            getInteger(subPath, "sectionTime"),
                            getDouble(subPath, "distance"),
                            getText(subPath, "startName"),
                            getText(subPath, "endName"),
                            getText(subPath, "startLocalStationID"),
                            getText(subPath, "endLocalStationID"),
                            busOptions
                    ));
                }
            }

            routes.add(new RouteInfo(
                    routeIndex++,
                    getInteger(info, "totalTime"),
                    getInteger(info, "totalDistance"),
                    getInteger(info, "busTransitCount"),
                    getInteger(info, "subwayTransitCount"),
                    segments
            ));
        }

        return new RouteSearchResponse(
                "ODsay",
                routes.size(),
                routes
        );
    }

    private static String getText(
            JsonNode parent,
            String fieldName
    ) {
        JsonNode node = parent.path(fieldName);

        if (node.isMissingNode() || node.isNull()) {
            return null;
        }

        return node.asString();
    }

    private static Integer getInteger(
            JsonNode parent,
            String fieldName
    ) {
        JsonNode node = parent.path(fieldName);

        if (node.isMissingNode() || node.isNull()) {
            return null;
        }

        return node.asInt();
    }

    private static Double getDouble(
            JsonNode parent,
            String fieldName
    ) {
        JsonNode node = parent.path(fieldName);

        if (node.isMissingNode() || node.isNull()) {
            return null;
        }

        return node.asDouble();
    }

    public record RouteSearchResponse(
            String provider,
            int count,
            List<RouteInfo> routes
    ) {
    }

    public record RouteInfo(
            int routeIndex,
            Integer totalTimeMin,
            Integer totalDistanceM,
            Integer busTransfers,
            Integer subwayTransfers,
            List<SegmentInfo> segments
    ) {
    }

    public record SegmentInfo(
            int segmentIndex,
            String mode,
            Integer durationMin,
            Double distanceM,
            String startName,
            String endName,
            String startLocalStationId,
            String endLocalStationId,
            List<BusOption> busOptions
    ) {
    }

    public record BusOption(
            String busNo,
            String localRouteId
    ) {
    }
}
