
package com.yongyong.transit.place.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class KakaoPlaceClient {

    private static final String BASE_URL =
        "https://dapi.kakao.com/v2/local/search/keyword.json";

    private final HttpClient httpClient;
    private final String apiKey;

    public KakaoPlaceClient(
        @Value("${kakao.rest-api-key:}") String apiKey
    ) {
        this.apiKey = apiKey;

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    public String searchPlaces(
        String query,
        Double x,
        Double y,
        Integer radius
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Kakao REST API 키가 설정되지 않았습니다."
            );
        }

        StringBuilder url = new StringBuilder(BASE_URL)
            .append("?query=")
            .append(URLEncoder.encode(
                query, StandardCharsets.UTF_8
            ))
            .append("&size=10");

        if (x != null && y != null) {
            url.append("&x=").append(x)
                .append("&y=").append(y)
                .append("&sort=distance");

            if (radius != null) {
                url.append("&radius=").append(radius);
            }
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url.toString()))
            .timeout(Duration.ofSeconds(20))
            .header(
                "Authorization",
                "KakaoAK " + apiKey.trim()
            )
            .header("Accept", "application/json")
            .GET()
            .build();

        try {
            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8
                )
            );

            if (response.statusCode() < 200
                || response.statusCode() >= 300) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Kakao 장소 검색 API 오류: HTTP "
                        + response.statusCode()
                );
            }

            return response.body();

        } catch (IOException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Kakao 서버와 통신할 수 없습니다.",
                e
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Kakao API 요청이 중단되었습니다.",
                e
            );
        }
    }
}
