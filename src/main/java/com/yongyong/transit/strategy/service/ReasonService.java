package com.yongyong.transit.strategy.service;

import com.yongyong.transit.analysis.service.RouteAnalysisService.SegmentView;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SeatType;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.Stage;
import com.yongyong.transit.common.client.OpenAiClient;
import com.yongyong.transit.strategy.service.StrategyService.Mode;
import com.yongyong.transit.strategy.service.StrategyService.StrategyType;
import com.yongyong.transit.strategy.service.StrategyService.StrategyView;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 근거 문장 생성.
 * 서버가 계산한 값만 사실 JSON으로 만들어 LLM에 한 번 보내고, 전략마다 문장을 받는다.
 * - 추천 전략은 1~2문장, 나머지는 1문장. 한 문장 40자 이내, 해요체
 * - 사실 JSON에 없는 숫자, 확률·퍼센트가 있으면 그 전략은 문장 틀을 쓴다
 * - LLM 오류·2.5초 초과·키 없음, 재평가는 모든 전략에 문장 틀을 쓴다
 * - 입력을 줄이려고 값이 없는(null) 필드는 보내지 않는다
 */
@Service
public class ReasonService {

    static final Duration LLM_TIMEOUT = Duration.ofMillis(2500);
    static final int MAX_CHARS = 40;

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    static final String INSTRUCTIONS = """
        너는 버스 이동 전략 카드에 붙일 추천 이유 문장을 쓴다.
        입력 JSON의 strategies마다 문장을 쓴다. recommended가 true인 전략은 1~2문장, 나머지는 1문장이다.
        규칙:
        - 한 문장은 공백 포함 40자 이내, 해요체(~요)로 끝낸다.
        - 입력에 있는 숫자만 그대로 쓴다. 새 숫자, 반올림, 확률, 퍼센트는 쓰지 않는다.
        - 입력에 없는 사실, 장소, 노선 이름은 쓰지 않는다.
        - 전략마다 가장 중요한 사실을 골라 쓴다. 위험한 점, 빨라지는 시간, 좌석, 여유 순으로 중요하다.
        - KEEP(현재 경로)은 다른 전략과 비교하지 말고 그 경로의 상태(여유, 좌석, 대기 시간)를 말한다.
        - 대안은 KEEP과 비교해 무엇이 좋고 나쁜지 말한다.
        필드 뜻:
        - diffMin: 현재 경로(KEEP)보다 빨리 도착하는 분. 음수면 그만큼 늦다.
        - extraWalkMin: 더 걷는 분. waitMin: 버스가 올 때까지 남은 분.
        - nextBusGapMin: 이번 버스와 다음 버스의 간격(분). remainSeats: 예상 빈자리 수.
        - 단계: OK 가능, CAUTION 주의, RISK 위험, UNKNOWN 정보 없음.
        - 좌석 필드(remainSeats, seatStage)가 없으면 좌석 이야기는 하지 않는다. 없는 필드는 모르는 값이다.
        답은 입력 strategies의 strategyType 값을 그대로 키로, 문장 배열을 값으로 하는 JSON 객체만 쓴다.
        """;

    private final OpenAiClient openAiClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public ReasonService(OpenAiClient openAiClient) {
        this.openAiClient = openAiClient;
    }

    // 순위가 정해진 전략에 reasons를 채운다
    List<StrategyView> withReasons(
        List<StrategyView> ranked,
        Mode mode,
        OffsetDateTime now
    ) {
        Integer keepValue = ranked.stream()
            .filter(StrategyView::isCurrent)
            .map(StrategyView::comparisonValueMin)
            .findFirst()
            .orElse(null);

        List<Fact> facts = ranked.stream()
            .map(strategy -> fact(strategy, keepValue, now))
            .toList();

        Map<StrategyType, List<String>> llm = mode == Mode.STRATEGY
            ? llmReasons(facts)
            : Map.of();

        List<StrategyView> result = new ArrayList<>();

        for (int i = 0; i < ranked.size(); i++) {
            Fact fact = facts.get(i);
            List<String> reasons = llm.containsKey(fact.strategyType())
                ? llm.get(fact.strategyType())
                : templates(fact);

            result.add(ranked.get(i).withReasons(reasons));
        }

        return result;
    }

    static Fact fact(StrategyView strategy, Integer keepValue, OffsetDateTime now) {
        SegmentView bus = strategy.analysis() == null || strategy.analysis().isEmpty()
            ? null
            : strategy.analysis().get(0);
        // 예측값이 아닌 좌석은 LLM이 말하지 않도록 보내지 않는다
        boolean predictedSeat = strategy.seatType() == SeatType.PREDICTED;

        return new Fact(
            strategy.strategyType(),
            strategy.recommended(),
            strategy.title(),
            bus == null ? null : bus.routeLabel(),
            strategy.moveToName(),
            strategy.comparisonValueMin(),
            keepValue == null || strategy.comparisonValueMin() == null
                ? null
                : keepValue - strategy.comparisonValueMin(),
            strategy.extraWalkMin(),
            strategy.strategyRiskStage(),
            bus == null ? null : bus.slackStage(),
            bus == null || !predictedSeat ? null : bus.seatStage(),
            predictedSeat ? strategy.remainSeats() : null,
            predictedSeat ? strategy.seatType() : null,
            minutes(now, strategy.busArrivalAt()),
            minutes(strategy.busArrivalAt(), strategy.nextBusArrivalAt())
        );
    }

    // LLM 결과 중 규칙을 지킨 전략만 돌려준다 (나머지는 문장 틀)
    private Map<StrategyType, List<String>> llmReasons(List<Fact> facts) {
        if (!openAiClient.isEnabled()) {
            return Map.of();
        }

        String input = factsJson(facts);
        Optional<String> answer = openAiClient.completeJson(
            INSTRUCTIONS, input, LLM_TIMEOUT
        );

        if (answer.isEmpty()) {
            return Map.of();
        }

        return parseValid(answer.get(), facts, allowedNumbers(input));
    }

    String factsJson(List<Fact> facts) {
        Map<String, Object> input = new LinkedHashMap<>();

        facts.stream()
            .filter(fact -> fact.strategyType() == StrategyType.KEEP)
            .findFirst()
            .ifPresent(keep -> {
                Map<String, Object> current = new LinkedHashMap<>();
                current.put("routeLabel", keep.routeLabel());
                current.put("comparisonValueMin", keep.comparisonValueMin());
                current.put("strategyRiskStage", keep.strategyRiskStage());
                current.put("slackStage", keep.slackStage());
                current.put("seatStage", keep.seatStage());
                current.values().removeIf(Objects::isNull);
                input.put("current", current);
            });
        input.put("strategies", facts);

        return jsonMapper.writeValueAsString(input);
    }

    Map<StrategyType, List<String>> parseValid(
        String answer,
        List<Fact> facts,
        Set<String> allowedNumbers
    ) {
        Map<StrategyType, List<String>> result = new LinkedHashMap<>();
        JsonNode root;

        try {
            root = jsonMapper.readTree(answer);
        } catch (RuntimeException e) {
            return result;
        }

        for (Fact fact : facts) {
            JsonNode node = root.path(fact.strategyType().name());

            if (!node.isArray()) {
                continue;
            }

            List<String> sentences = new ArrayList<>();
            node.forEach(item -> sentences.add(
                item.isString() ? item.asString().trim() : ""
            ));

            if (valid(sentences, fact.recommended(), allowedNumbers)) {
                result.put(fact.strategyType(), sentences);
            }
        }

        return result;
    }

    static boolean valid(
        List<String> sentences,
        boolean recommended,
        Set<String> allowedNumbers
    ) {
        int max = recommended ? 2 : 1;

        if (sentences.isEmpty() || sentences.size() > max) {
            return false;
        }

        for (String sentence : sentences) {
            String body = sentence.replaceAll("[.!\\s]+$", "");

            if (body.isEmpty()
                || sentence.codePointCount(0, sentence.length()) > MAX_CHARS
                || !body.endsWith("요")
                || sentence.contains("%")
                || sentence.contains("확률")
                || sentence.contains("퍼센트")) {
                return false;
            }

            Matcher matcher = NUMBER.matcher(sentence);

            while (matcher.find()) {
                if (!allowedNumbers.contains(matcher.group())) {
                    return false;
                }
            }
        }

        return true;
    }

    static Set<String> allowedNumbers(String factsJson) {
        Set<String> numbers = new HashSet<>();
        Matcher matcher = NUMBER.matcher(factsJson);

        while (matcher.find()) {
            numbers.add(matcher.group());
        }

        return numbers;
    }

    /**
     * 문장 틀: 상황에서 가장 중요한 사실 1~2개를 골라 붙인다.
     * 첫 문장은 위험한 점(KEEP) 또는 빨라지는 시간(대안), 추천 전략만 좌석·여유 문장을 더한다.
     */
    static List<String> templates(Fact fact) {
        List<String> reasons = new ArrayList<>();
        reasons.add(fact.strategyType() == StrategyType.KEEP
            ? keepSentence(fact)
            : gainSentence(fact));

        // KEEP은 위험한 점을 이미 말했으면 더하지 않는다
        boolean addExtra = fact.recommended()
            && (fact.strategyType() != StrategyType.KEEP
                || !risky(fact.slackStage()) && !risky(fact.seatStage()));

        if (addExtra) {
            String extra = extraSentence(fact);

            if (extra != null && !reasons.contains(extra)) {
                reasons.add(extra);
            }
        }

        return reasons;
    }

    private static String keepSentence(Fact fact) {
        if (fact.slackStage() == null && fact.seatStage() == null) {
            return "예정대로 가면 돼요";
        }

        if (fact.slackStage() == Stage.RISK) {
            return "지금 가면 버스를 놓칠 수 있어요";
        }

        if (fact.seatStage() == Stage.RISK) {
            return "타려는 버스에 빈자리가 없을 수 있어요";
        }

        if (fact.seatStage() == Stage.CAUTION) {
            return "타려는 버스에 빈자리가 거의 없을 수 있어요";
        }

        if (fact.slackStage() == Stage.CAUTION) {
            return "버스 도착까지 여유가 빠듯해요";
        }

        if (fact.strategyRiskStage() == Stage.OK) {
            return "지금 경로로 여유 있게 탈 수 있어요";
        }

        return "실시간 정보가 부족해 주의가 필요해요";
    }

    private static String gainSentence(Fact fact) {
        Integer diff = fact.diffMin();

        if (diff == null) {
            return switch (fact.strategyType()) {
                case BACKTRACK -> "앞 정류장에서 탈 수 있어요";
                case ALT_ROUTE -> fit(
                    "같은 정류장에서 " + fact.routeLabel() + "번도 탈 수 있어요",
                    "같은 정류장에서 다른 노선도 탈 수 있어요"
                );
                case DETOUR -> "다른 정류장에서 타는 방법이에요";
                default -> "버스를 기다리지 않아도 돼요";
            };
        }

        if (diff < 0) {
            return "지금 경로보다 " + (-diff) + "분 늦게 도착해요";
        }

        if (diff == 0) {
            return "지금 경로와 비슷하게 도착해요";
        }

        return switch (fact.strategyType()) {
            case BACKTRACK -> fact.extraWalkMin() > 0
                ? "앞 정류장까지 " + fact.extraWalkMin() + "분 더 걸으면 "
                    + diff + "분 빨리 도착해요"
                : "앞 정류장에서 타면 " + diff + "분 빨리 도착해요";
            case ALT_ROUTE -> fit(
                fact.routeLabel() + "번으로 바꾸면 " + diff + "분 빨리 도착해요",
                "다른 노선으로 바꾸면 " + diff + "분 빨리 도착해요"
            );
            case DETOUR -> "다른 정류장에서 타면 " + diff + "분 빨리 도착해요";
            default -> "버스 대신 가면 " + diff + "분 빨리 도착해요";
        };
    }

    // 추천 전략의 두 번째 문장: 좌석 → 여유 → (다른 교통수단) 기다림 없음
    private static String extraSentence(Fact fact) {
        if (fact.seatType() == SeatType.PREDICTED && fact.seatStage() == Stage.OK) {
            return "자리도 넉넉할 것으로 예상돼요";
        }

        if (fact.seatStage() == Stage.CAUTION) {
            return "빈자리가 많지 않을 수 있어요";
        }

        if (fact.slackStage() == Stage.OK) {
            return "버스 도착까지 여유가 있어요";
        }

        if (fact.strategyType() == StrategyType.OTHER_MODE) {
            return "버스를 기다리지 않아도 돼요";
        }

        return null;
    }

    private static boolean risky(Stage stage) {
        return stage == Stage.CAUTION || stage == Stage.RISK;
    }

    // 노선 이름이 길어 40자를 넘으면 짧은 문장
    private static String fit(String sentence, String shorter) {
        return sentence.codePointCount(0, sentence.length()) <= MAX_CHARS
            && !sentence.contains("null")
            ? sentence
            : shorter;
    }

    private static Integer minutes(OffsetDateTime from, OffsetDateTime to) {
        return from == null || to == null
            ? null
            : (int) Duration.between(from, to).toMinutes();
    }

    /**
     * LLM에 보내는 전략 하나의 사실. 위치 좌표·주소·기기 ID는 넣지 않는다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Fact(
        StrategyType strategyType,
        boolean recommended,
        String title,
        String routeLabel,
        String moveToName,
        Integer comparisonValueMin,
        Integer diffMin,
        int extraWalkMin,
        Stage strategyRiskStage,
        Stage slackStage,
        Stage seatStage,
        Integer remainSeats,
        SeatType seatType,
        Integer waitMin,
        Integer nextBusGapMin
    ) {
    }
}
