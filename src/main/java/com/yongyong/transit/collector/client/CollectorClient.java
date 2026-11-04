
package com.yongyong.transit.collector.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class CollectorClient {

    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final String baseUrl;

    public CollectorClient(
        @Value("${COLLECTOR_BASE_URL:}") String baseUrl
    ) {
        this.baseUrl = baseUrl == null
            ? ""
            : baseUrl.trim().replaceAll("/+$", "");

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

        this.jsonMapper = JsonMapper.builder().build();
    }

    public JsonNode getRoutes() {
        return get("/api/routes");
    }

    public JsonNode getLocations() {
        return get("/api/latest/locations");
    }

    public JsonNode getArrivals() {
        return get("/api/latest/arrivals");
    }

    private JsonNode get(String path) {
        if (baseUrl.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "COLLECTOR_BASE_URL is not configured"
            );
        }

        URI uri;
        try {
            uri = URI.create(baseUrl + path);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Invalid collector URL",
                e
            );
        }

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

            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Collector request interrupted",
                e
            );
        } catch (IOException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Failed to connect to collector",
                e
            );
        }

        if (response.statusCode() < 200
            || response.statusCode() >= 300) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Collector returned HTTP "
                    + response.statusCode()
            );
        }

        try {
            JsonNode result = jsonMapper.readTree(
                response.body()
            );

            if (result == null) {
                throw new IllegalStateException(
                    "Empty collector response"
                );
            }

            return result;
        } catch (Exception e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Invalid collector JSON response",
                e
            );
        }
    }
}
