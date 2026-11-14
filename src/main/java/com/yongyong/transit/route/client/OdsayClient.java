
package com.yongyong.transit.route.client;

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
public class OdsayClient {

    private static final String BASE_URL =
            "https://api.odsay.com/v1/api/searchPubTransPathT";

    private final HttpClient httpClient;
    private final String apiKey;

    public OdsayClient(@Value("${odsay.api-key:}") String apiKey) {
        this.apiKey = apiKey;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public String searchRoutes(
            double startX,
            double startY,
            double endX,
            double endY
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ODsay API 키가 설정되지 않았습니다."
            );
        }

        String encodedKey = URLEncoder.encode(
                apiKey.trim(),
                StandardCharsets.UTF_8
        );

        String url = BASE_URL
                + "?apiKey=" + encodedKey
                + "&SX=" + startX
                + "&SY=" + startY
                + "&EX=" + endX
                + "&EY=" + endY
                + "&SearchType=0"
                + "&SearchPathType=0"
                + "&output=json";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
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
                        "ODsay 서버 HTTP 오류: " + response.statusCode()
                );
            }

            return response.body();

        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "ODsay 서버와 통신할 수 없습니다.",
                    e
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ODsay API 요청이 중단되었습니다.",
                    e
            );
        }
    }
}
