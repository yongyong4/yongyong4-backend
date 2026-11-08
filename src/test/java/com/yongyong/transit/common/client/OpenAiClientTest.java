package com.yongyong.transit.common.client;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiClientTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void 키가_없으면_부르지_않고_빈_값() {
        OpenAiClient client = new OpenAiClient("", "", "");

        assertThat(client.isEnabled()).isFalse();
        assertThat(client.completeJson("지시", "{}", Duration.ofMillis(100)))
            .isEmpty();
    }

    @Test
    void 모델을_넣지_않으면_루나_넣으면_그_모델() {
        assertThat(new OpenAiClient("key", "", "").model())
            .isEqualTo("gpt-5.6-luna");
        assertThat(new OpenAiClient("key", " gpt-5.4-mini ", "").model())
            .isEqualTo("gpt-5.4-mini");
    }

    @Test
    void 요청은_JSON_응답_형식과_빠른_추론으로_보냄() {
        OpenAiClient client = new OpenAiClient("key", "", "");

        JsonNode body = json.readTree(client.requestBody("지시문", "{\"a\":1}"));

        assertThat(body.path("model").asString()).isEqualTo("gpt-5.6-luna");
        assertThat(body.path("reasoning_effort").asString()).isEqualTo("none");
        assertThat(body.path("response_format").path("type").asString())
            .isEqualTo("json_object");
        assertThat(body.path("messages").path(0).path("role").asString())
            .isEqualTo("system");
        assertThat(body.path("messages").path(1).path("content").asString())
            .isEqualTo("{\"a\":1}");
    }

    @Test
    void 응답에서_첫_메시지_내용을_꺼냄() {
        OpenAiClient client = new OpenAiClient("key", "", "");

        assertThat(client.parseContent("""
            {"choices":[{"message":{"role":"assistant","content":"{\\"KEEP\\":[\\"좋아요\\"]}"}}]}
            """)).contains("{\"KEEP\":[\"좋아요\"]}");
        assertThat(client.parseContent("{\"choices\":[]}")).isEmpty();
        assertThat(client.parseContent("not json")).isEmpty();
    }
}
