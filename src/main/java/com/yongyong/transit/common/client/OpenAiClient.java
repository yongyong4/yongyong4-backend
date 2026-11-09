package com.yongyong.transit.common.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * OpenAI Chat Completions 호출 (공통).
 * 지시문과 입력을 보내 JSON 객체 문자열을 받는다. 기능별 지시문·검사는 쓰는 쪽에서 한다.
 * - OPENAI_API_KEY가 없으면 사용하지 않는다
 * - OPENAI_MODEL이 없으면 gpt-5.6-luna, OPENAI_REASONING_EFFORT가 없으면 none(빠른 응답)
 * - 오류·시간 초과면 예외 없이 빈 값을 돌려주고, 쓰는 쪽이 대체 문장을 쓴다
 */
@Component
public class OpenAiClient {

    static final String DEFAULT_MODEL = "gpt-5.6-luna";
    static final String DEFAULT_REASONING_EFFORT = "none";

    private static final String CHAT_URL =
        "https://api.openai.com/v1/chat/completions";

    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final String apiKey;
    private final String model;
    private final String reasoningEffort;

    public OpenAiClient(
        @Value("${OPENAI_API_KEY:}") String apiKey,
        @Value("${OPENAI_MODEL:}") String model,
        @Value("${OPENAI_REASONING_EFFORT:}") String reasoningEffort
    ) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank()
            ? DEFAULT_MODEL
            : model.trim();
        this.reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank()
            ? DEFAULT_REASONING_EFFORT
            : reasoningEffort.trim();

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .build();

        this.jsonMapper = JsonMapper.builder().build();
    }

    public boolean isEnabled() {
        return !apiKey.isBlank();
    }

    public String model() {
        return model;
    }

    /**
     * instructions: 시스템 지시문 (JSON으로 답하라는 말을 넣어야 한다)
     * input: 사용자 메시지 (보통 사실 JSON)
     * timeout: 연결부터 응답까지 전체 시간
     */
    public Optional<String> completeJson(
        String instructions,
        String input,
        Duration timeout
    ) {
        if (!isEnabled()) {
            return Optional.empty();
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(CHAT_URL))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(
                requestBody(instructions, input),
                StandardCharsets.UTF_8
            ))
            .build();

        CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(
            request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        try {
            HttpResponse<String> response =
                future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }

            return parseContent(response.body());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return Optional.empty();
        } catch (TimeoutException | ExecutionException | RuntimeException e) {
            future.cancel(true);
            return Optional.empty();
        }
    }

    String requestBody(String instructions, String input) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("model", model);
        body.put("reasoning_effort", reasoningEffort);
        body.putObject("response_format").put("type", "json_object");

        var messages = body.putArray("messages");
        messages.addObject()
            .put("role", "system")
            .put("content", instructions);
        messages.addObject()
            .put("role", "user")
            .put("content", input);

        return jsonMapper.writeValueAsString(body);
    }

    // choices[0].message.content
    Optional<String> parseContent(String body) {
        try {
            JsonNode content = jsonMapper.readTree(body)
                .path("choices").path(0).path("message").path("content");

            if (!content.isString() || content.asString().isBlank()) {
                return Optional.empty();
            }

            return Optional.of(content.asString());

        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
