package com.yongyong.transit.analysis.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 도착·좌석 예측 서버(POST /predict) 호출.
 * PREDICT_BASE_URL이 없으면 사용하지 않는다. 실패하거나 1초를 넘기면 예외를 던지고,
 * 호출한 쪽은 실시간 값을 그대로 쓴다.
 */
@Component
public class PredictClient {

    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final String baseUrl;

    public PredictClient(
        @Value("${PREDICT_BASE_URL:}") String baseUrl
    ) {
        this.baseUrl = baseUrl == null
            ? ""
            : baseUrl.trim().replaceAll("/+$", "");

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .build();

        this.jsonMapper = JsonMapper.builder().build();
    }

    public boolean isEnabled() {
        return !baseUrl.isBlank();
    }

    public PredictResult predict(PredictRequest request) {
        if (!isEnabled()) {
            throw new IllegalStateException("PREDICT_BASE_URL is not configured");
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/predict"))
            .timeout(Duration.ofSeconds(1))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                toJson(request),
                StandardCharsets.UTF_8
            ))
            .build();

        HttpResponse<String> response;

        try {
            response = httpClient.send(
                httpRequest,
                HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8
                )
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Predict request interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to connect to predict server", e);
        }

        if (response.statusCode() < 200
            || response.statusCode() >= 300) {
            throw new IllegalStateException(
                "Predict server returned HTTP " + response.statusCode()
            );
        }

        return parse(response.body());
    }

    String toJson(PredictRequest request) {
        ObjectNode root = jsonMapper.createObjectNode();
        root.put("gbisRouteId", request.gbisRouteId());
        root.put("boardStationId", request.boardStationId());
        root.put("requestAt", iso(request.requestAt()));

        ArrayNode arrivals = root.putArray("arrivals");

        for (PredictArrival arrival : request.arrivals()) {
            ObjectNode item = arrivals.addObject();
            item.put("plateNo", arrival.plateNo());
            item.put(
                "predictedBusArrivalAt",
                iso(arrival.predictedBusArrivalAt())
            );

            if (arrival.remainSeats() == null) {
                item.putNull("remainSeats");
            } else {
                item.put("remainSeats", arrival.remainSeats());
            }
        }

        return root.toString();
    }

    PredictResult parse(String body) {
        JsonNode root;

        try {
            root = jsonMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid predict JSON response", e);
        }

        if (root == null || !root.path("predictions").isArray()) {
            throw new IllegalStateException("Invalid predict JSON response");
        }

        List<Prediction> predictions = new ArrayList<>();

        for (JsonNode item : root.path("predictions")) {
            JsonNode seats = item.path("predictedRemainSeats");

            predictions.add(new Prediction(
                item.path("plateNo").asText(),
                parseTime(item.path("predictedBusArrivalAt").asText()),
                seats.isNumber() ? seats.asInt() : null
            ));
        }

        JsonNode modelVersion = root.path("modelVersion");

        return new PredictResult(
            predictions,
            modelVersion.isMissingNode() || modelVersion.isNull()
                ? null
                : modelVersion.asText()
        );
    }

    // 명세 예시처럼 초까지 표기 (2026-11-04T07:30:00+09:00)
    private String iso(OffsetDateTime time) {
        return time.truncatedTo(ChronoUnit.SECONDS)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private OffsetDateTime parseTime(String value) {
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    public record PredictRequest(
        String gbisRouteId,
        String boardStationId,
        OffsetDateTime requestAt,
        List<PredictArrival> arrivals
    ) {
    }

    public record PredictArrival(
        String plateNo,
        OffsetDateTime predictedBusArrivalAt,
        Integer remainSeats
    ) {
    }

    public record PredictResult(
        List<Prediction> predictions,
        String modelVersion
    ) {
    }

    public record Prediction(
        String plateNo,
        OffsetDateTime predictedBusArrivalAt,
        Integer predictedRemainSeats
    ) {
    }
}
