
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
                                        getText(lane, "busLocalBlID"),
                                        busType(getInteger(lane, "type"))
                                ));
                            }
                        }
                    }

                    String lineName = null;

                    if (trafficType == 1) {
                        JsonNode lane = subPath.path("lane").path(0);

                        lineName = lineName(
                                getInteger(lane, "subwayCode"),
                                getText(lane, "name")
                        );
                    }

                    // 도보 구간은 ODsay가 좌표를 주지 않아 null, passStops는 빈 배열
                    segments.add(new SegmentInfo(
                            segmentIndex++,
                            mode,
                            getInteger(subPath, "sectionTime"),
                            getDouble(subPath, "distance"),
                            getText(subPath, "startName"),
                            getText(subPath, "endName"),
                            getText(subPath, "startLocalStationID"),
                            getText(subPath, "endLocalStationID"),
                            getDouble(subPath, "startX"),
                            getDouble(subPath, "startY"),
                            getDouble(subPath, "endX"),
                            getDouble(subPath, "endY"),
                            lineName,
                            busOptions,
                            passStops(subPath)
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

    // 버스·지하철 구간이 지나는 정류장(역) 좌표, 탑승 정류장부터 하차 정류장까지 순서대로
    // 지도에서 이 점들을 이어 경로 선을 그린다 (추가 ODsay 호출 없음)
    private static List<PassStop> passStops(JsonNode subPath) {
        JsonNode stations = subPath.path("passStopList").path("stations");
        List<PassStop> stops = new ArrayList<>();

        if (!stations.isArray()) {
            return stops;
        }

        for (JsonNode station : stations) {
            Double x = getCoordinate(station, "x");
            Double y = getCoordinate(station, "y");

            if (x == null || y == null) {
                continue;
            }

            stops.add(new PassStop(
                    getText(station, "stationName"),
                    x,
                    y
            ));
        }

        return stops;
    }

    // ODsay 정류장 좌표는 "127.15" 처럼 문자열로 온다
    private static Double getCoordinate(
            JsonNode parent,
            String fieldName
    ) {
        JsonNode node = parent.path(fieldName);

        if (node.isNumber()) {
            return node.asDouble();
        }

        if (!node.isString()) {
            return null;
        }

        try {
            return Double.parseDouble(node.asString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ODsay 버스 type → 프론트 노선 배지 이름
    // 용인 5000번대는 직행좌석(4)으로 와서 광역(14)과 같이 광역으로 본다
    static String busType(Integer type) {
        if (type == null) {
            return "일반";
        }

        return switch (type) {
            case 4, 14 -> "광역";
            case 3 -> "마을";
            default -> "일반";
        };
    }

    // ODsay 지하철 노선 → 프론트 노선 배지 이름
    // 수인분당선은 ODsay 이름이 "수도권 수인.분당선"이라 코드로 맞춘다
    static String lineName(Integer subwayCode, String name) {
        if (subwayCode != null && subwayCode == 107) {
            return "에버라인";
        }

        if (subwayCode != null && subwayCode == 116) {
            return "수인분당";
        }

        if (name == null) {
            return null;
        }

        return name.replaceFirst("^수도권\\s*", "");
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
            Double startX,
            Double startY,
            Double endX,
            Double endY,
            String lineName,
            List<BusOption> busOptions,
            List<PassStop> passStops
    ) {
    }

    public record BusOption(
            String busNo,
            String localRouteId,
            String busType
    ) {
    }

    public record PassStop(
            String name,
            double x,
            double y
    ) {
    }
}
