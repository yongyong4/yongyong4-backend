package com.yongyong.transit.analysis.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * GBIS 조회.
 * - 정류장 도착정보: 그 정류장에 오는 노선 전체와 노선마다 다음 버스 2대
 * - 노선 경유 정류장 목록, 정류장 경유 노선 목록 (정적 정보, 대안 생성용)
 */
@Component
public class GbisClient {

    private static final String ARRIVAL_URL =
        "https://apis.data.go.kr/6410000/busarrivalservice/v2/getBusArrivalListv2";

    private static final String ROUTE_STATION_URL =
        "https://apis.data.go.kr/6410000/busrouteservice/v2/getBusRouteStationListv2";

    private static final String STATION_ROUTE_URL =
        "https://apis.data.go.kr/6410000/busstationservice/v2/getBusStationViaRouteListv2";

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    // GBIS resultCode: 0 정상, 4 결과 없음(운행 버스 없음)
    private static final int RESULT_OK = 0;
    private static final int RESULT_NO_DATA = 4;

    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final String apiKey;

    public GbisClient(
        @Value("${GBIS_API_KEY:}") String apiKey
    ) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

        this.jsonMapper = JsonMapper.builder().build();
    }

    public boolean hasKey() {
        return !apiKey.isBlank();
    }

    public GbisArrivals getStationArrivals(String stationId) {
        return parse(fetch(ARRIVAL_URL, "stationId", stationId));
    }

    public List<RouteStation> getRouteStations(String routeId) {
        return parseRouteStations(fetch(ROUTE_STATION_URL, "routeId", routeId));
    }

    public List<StationRoute> getStationRoutes(String stationId) {
        return parseStationRoutes(
            fetch(STATION_ROUTE_URL, "stationId", stationId)
        );
    }

    private String fetch(
        String url,
        String paramName,
        String paramValue
    ) {
        if (!hasKey()) {
            throw new IllegalStateException("GBIS_API_KEY is not configured");
        }

        String key = apiKey.contains("%")
            ? apiKey
            : URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

        URI uri = URI.create(
            url
                + "?" + paramName + "="
                + URLEncoder.encode(paramValue, StandardCharsets.UTF_8)
                + "&format=json&serviceKey="
                + key
        );

        HttpRequest request = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(3))
            .header("Accept", "application/json")
            .GET()
            .build();

        HttpResponse<String> response;

        try {
            response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8
                )
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GBIS request interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to connect to GBIS", e);
        }

        if (response.statusCode() < 200
            || response.statusCode() >= 300) {
            throw new IllegalStateException(
                "GBIS returned HTTP " + response.statusCode()
            );
        }

        return response.body();
    }

    GbisArrivals parse(String body) {
        JsonNode response = readResponse(body);

        OffsetDateTime queryTime = parseQueryTime(
            text(response.path("msgHeader"), "queryTime")
        );

        List<JsonNode> items = items(response, "busArrivalList");

        List<GbisBus> buses = new ArrayList<>();

        for (JsonNode item : items) {
            for (int order = 1; order <= 2; order++) {
                Integer predictSec = integer(item, "predictTimeSec" + order);
                Integer predictMin = integer(item, "predictTime" + order);

                if (predictSec == null && predictMin == null) {
                    continue;
                }

                buses.add(new GbisBus(
                    text(item, "routeId"),
                    text(item, "routeName"),
                    text(item, "plateNo" + order),
                    predictSec != null ? predictSec : predictMin * 60,
                    integer(item, "remainSeatCnt" + order)
                ));
            }
        }

        return new GbisArrivals(queryTime, buses);
    }

    List<RouteStation> parseRouteStations(String body) {
        List<RouteStation> stations = new ArrayList<>();

        for (JsonNode item : items(readResponse(body), "busRouteStationList")) {
            String stationId = text(item, "stationId");
            Integer seq = integer(item, "stationSeq");

            if (stationId == null || seq == null) {
                continue;
            }

            stations.add(new RouteStation(
                stationId,
                text(item, "stationName"),
                seq,
                decimal(item, "x"),
                decimal(item, "y")
            ));
        }

        return stations;
    }

    List<StationRoute> parseStationRoutes(String body) {
        List<StationRoute> routes = new ArrayList<>();

        for (JsonNode item : items(readResponse(body), "busRouteList")) {
            String routeId = text(item, "routeId");

            if (routeId == null) {
                continue;
            }

            routes.add(new StationRoute(routeId, text(item, "routeName")));
        }

        return routes;
    }

    /**
     * 게이트웨이 오류·resultCode를 확인하고 response 노드를 돌려준다.
     * 결과 없음(4)이면 msgBody가 비어 있어 목록이 빈 값이 된다.
     */
    private JsonNode readResponse(String body) {
        JsonNode root;

        try {
            root = jsonMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid GBIS JSON response", e);
        }

        if (root == null || !root.isObject()) {
            throw new IllegalStateException("Invalid GBIS JSON response");
        }

        // 인증키 미등록, 한도 초과 등 data.go.kr 게이트웨이 오류
        if (root.has("OpenAPI_ServiceResponse")) {
            throw new IllegalStateException("GBIS gateway error");
        }

        JsonNode response = root.path("response");
        Integer resultCode = integer(response.path("msgHeader"), "resultCode");

        if (resultCode == null) {
            throw new IllegalStateException("GBIS resultCode is missing");
        }

        if (resultCode != RESULT_OK && resultCode != RESULT_NO_DATA) {
            throw new IllegalStateException(
                "GBIS resultCode " + resultCode
            );
        }

        return response;
    }

    // 항목이 하나면 배열이 아니라 객체로 온다
    private List<JsonNode> items(
        JsonNode response,
        String listField
    ) {
        JsonNode list = response.path("msgBody").path(listField);

        List<JsonNode> items = new ArrayList<>();

        if (list.isArray()) {
            list.forEach(items::add);
        } else if (list.isObject()) {
            items.add(list);
        }

        return items;
    }

    // "2026-10-28 06:00:12.345" 형식, 오프셋 없으면 KST
    private OffsetDateTime parseQueryTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.trim().replace(' ', 'T');

        try {
            return OffsetDateTime.parse(normalized);
        } catch (Exception ignored) {
            // 오프셋 없는 형식
        }

        try {
            return LocalDateTime.parse(normalized).atOffset(KST);
        } catch (Exception e) {
            return null;
        }
    }

    private String text(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.path(field);

        if (value.isMissingNode() || value.isNull()) {
            return null;
        }

        String text = value.asText();

        return text.isBlank() ? null : text;
    }

    private Integer integer(
        JsonNode node,
        String field
    ) {
        String text = text(node, field);

        if (text == null) {
            return null;
        }

        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double decimal(
        JsonNode node,
        String field
    ) {
        String text = text(node, field);

        if (text == null) {
            return null;
        }

        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record GbisArrivals(
        OffsetDateTime queryTime,
        List<GbisBus> buses
    ) {
    }

    public record GbisBus(
        String routeId,
        String routeName,
        String plateNo,
        int predictSec,
        Integer remainSeats
    ) {
    }

    // 노선 경유 정류장 (seq 순서), x 경도 / y 위도
    public record RouteStation(
        String stationId,
        String name,
        int seq,
        Double x,
        Double y
    ) {
    }

    public record StationRoute(
        String routeId,
        String routeLabel
    ) {
    }
}
