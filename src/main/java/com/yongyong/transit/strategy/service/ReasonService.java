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
 * - 사실 JSON에 없는 숫자, 확률·퍼센트, 내부 용어("추천", "단계", 필드 값)가 있으면 그 전략은 문장 틀을 쓴다
 * - 주의·위험이면 이유(cautionCause)를 서버가 계산해 넣는다
 * - LLM 오류·2.5초 초과·키 없음, 재평가는 모든 전략에 문장 틀을 쓴다
 * - 입력을 줄이려고 값이 없는(null) 필드는 보내지 않는다
 */
@Service
public class ReasonService {

    static final Duration LLM_TIMEOUT = Duration.ofMillis(2500);
    static final int MAX_CHARS = 40;
    // 이만큼 이하로 빨라지면 시간보다 다른 장점(좌석·여유)을 먼저 말한다
    static final int SMALL_GAIN_MIN = 2;

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    // 문장에 쓰면 안 되는 말 (내부 용어·필드 값, 틀린 용어, 좌석 단정)
    private static final List<String> BANNED_WORDS = List.of(
        "추천", "단계", "OK", "CAUTION", "RISK", "UNKNOWN",
        "SLACK", "SEAT", "NO_INFO", "PREDICTED", "OBSERVED", "Min",
        "앞 정류장", "앉아"
    );

    static final String INSTRUCTIONS = """
        너는 출근길 이용자가 어떤 방법으로 갈지 고를 수 있게 전략 카드마다 이유 문장을 쓴다.
        입력 JSON의 strategies마다 문장 배열을 만든다.

        전략 종류
        - KEEP: 지금 경로 그대로 가기. BACKTRACK: 같은 노선을 버스가 지나온 이전 정류장으로 거슬러 걸어가서 타기.
        - ALT_ROUTE: 같은 정류장에서 다른 노선으로 바꿔 타기. DETOUR: 다른 정류장까지 걸어가서 다른 노선 타기.
        - OTHER_MODE: 버스 대신 다른 교통수단으로 가기. bySubway가 true면 지하철, 없으면 "다른 교통수단"이라고 쓴다.

        문장 수
        - recommended가 true인 전략: 첫 문장은 왜 가장 나은지(빨라지는 시간, 안전함)를 말한다.
          단, diffMin이 2 이하이거나 없으면 시간 차이는 작으니 첫 문장에 좌석·여유처럼 실제로 나은 점을 쓰고,
          더 걷는 시간은 두 번째 문장에 쓴다. 더 걷는 시간으로 문장을 시작하지 않는다.
          더 걷는 시간, 대기 시간, 예상 좌석, 주의 이유, 놓쳤을 때 기다릴 시간 중 알려 줄 사실이 있으면 두 번째 문장으로 쓰고, 없으면 한 문장만 쓴다.
        - 나머지 전략: 한 문장. 좋은 점이 크면 좋은 점을, 단점이 크면 단점을 말한다.
        - slackStage가 RISK인 전략은 "놓칠 수 있어요"나 "놓치면 nextBusGapMin분 더 기다려요"처럼 놓칠 위험을 꼭 쓴다.
        - KEEP은 다른 전략과 비교하지 않고 그 경로의 상태(여유, 좌석, 대기 시간, 주의 이유)를 말한다.
          단, KEEP이 recommended인데 주의·위험이고 다른 전략도 있으면, 두 번째 문장에 "그래도 다른 방법보다 늦지 않아요"처럼 그래도 나은 이유를 쓴다.

        규칙
        - 한 문장은 공백 포함 40자 이내, 해요체(~요)로 끝낸다.
        - 각 전략 문장에는 그 전략에 있는 숫자만 쓴다. 다른 전략의 숫자, 새 숫자, 반올림, 확률, 퍼센트는 쓰지 않는다.
        - diffMin이 양수면 빨리, 음수면 늦게 도착하는 것이다. 반대로 쓰지 않는다.
        - 문장만 읽어도 어떤 방법인지 알 수 있게 쓴다("13분 늦게 도착해요"가 아니라 "지하철로 가면 13분 늦어요").
        - 정류장·역 이름은 쓰지 않는다("이전 정류장", "다른 정류장"으로 쓴다. "앞 정류장"이라고 쓰지 않는다). 입력에 없는 사실, 노선 이름도 쓰지 않는다. 노선은 routeLabel 그대로 "routeLabel번"으로 쓴다.
        - "추천", "단계"라는 말과 필드 이름·영어 값(OK, CAUTION 등)은 쓰지 않는다.
        - 좌석은 예상값이라 "앉아 갈 수 있어요"처럼 단정하지 않는다.
        - 더 걷는데 늦어지면 "더 걷고 늦어요"처럼 쓴다("걸어도"는 이득이 있을 때만 쓴다).
        - 전략마다 문장 시작을 다르게 쓴다.

        필드 뜻 (없는 필드는 모르는 값이다)
        - diffMin: 지금 경로(KEEP)보다 빨리 도착하는 분. 음수면 그만큼 늦다.
        - extraWalkMin: 더 걷는 분. waitMin: 버스가 올 때까지 남은 분.
        - nextBusGapMin: 이 버스를 놓치면 다음 버스까지 더 기다리는 분. 주의·위험인 전략에서 특히 알려 준다.
        - remainSeats, seatStage: 예상 빈자리 수와 좌석 상태. 없으면 좌석 이야기는 하지 않는다.
        - strategyRiskStage, slackStage, seatStage: OK 여유, CAUTION 주의, RISK 위험.
        - cautionCause: 주의·위험인 이유. SLACK 버스 도착까지 여유 부족, SEAT 빈자리 부족, NO_INFO 실시간 정보 부족.

        예시 (예시의 숫자는 쓰지 말고, 실제 답은 입력 숫자만 쓴다)
        입력: {"strategies":[{"strategyType":"ALT_ROUTE","recommended":true,"routeLabel":"5005","diffMin":9,"extraWalkMin":0,"remainSeats":12,"seatStage":"OK","waitMin":8},{"strategyType":"KEEP","recommended":false,"routeLabel":"5003A","strategyRiskStage":"CAUTION","seatStage":"CAUTION","remainSeats":2,"waitMin":6,"cautionCause":"SEAT"},{"strategyType":"DETOUR","recommended":false,"diffMin":-4,"extraWalkMin":5}]}
        답: {"ALT_ROUTE":["5005번으로 바꾸면 9분 빨리 도착해요","예상 빈자리도 12석 남아 있어요"],"KEEP":["타려는 버스에 빈자리가 2석뿐일 수 있어요"],"DETOUR":["다른 정류장까지 5분 더 걷고 4분 늦게 도착해요"]}
        입력: {"strategies":[{"strategyType":"KEEP","recommended":true,"routeLabel":"5003B","strategyRiskStage":"OK","slackStage":"OK","waitMin":6},{"strategyType":"BACKTRACK","recommended":false,"diffMin":-2,"extraWalkMin":5}]}
        답: {"KEEP":["6분 뒤 오는 버스를 여유 있게 탈 수 있어요"],"BACKTRACK":["이전 정류장까지 걸어가면 2분 늦어져요"]}
        입력: {"strategies":[{"strategyType":"BACKTRACK","recommended":true,"routeLabel":"5003A","diffMin":1,"extraWalkMin":4,"remainSeats":16,"seatStage":"OK","waitMin":7},{"strategyType":"KEEP","recommended":false,"routeLabel":"5003A","strategyRiskStage":"CAUTION","seatStage":"CAUTION","remainSeats":1,"waitMin":5,"cautionCause":"SEAT"}]}
        답: {"BACKTRACK":["이전 정류장에서 타면 예상 빈자리가 16석이에요","4분 더 걷지만 도착 시간은 비슷해요"],"KEEP":["5003A번은 빈자리가 1석뿐일 수 있어요"]}
        입력: {"strategies":[{"strategyType":"OTHER_MODE","recommended":true,"bySubway":true,"diffMin":11},{"strategyType":"KEEP","recommended":false,"strategyRiskStage":"RISK","slackStage":"RISK","waitMin":3,"nextBusGapMin":15,"cautionCause":"SLACK"}]}
        답: {"OTHER_MODE":["지하철로 가면 버스를 기다리지 않고 11분 빨리 도착해요"],"KEEP":["3분 뒤 버스라 빠듯하고 놓치면 15분 더 기다려요"]}
        입력: {"strategies":[{"strategyType":"KEEP","recommended":true,"routeLabel":"5003A","strategyRiskStage":"CAUTION","slackStage":"CAUTION","waitMin":4,"nextBusGapMin":20,"cautionCause":"SLACK"},{"strategyType":"DETOUR","recommended":false,"diffMin":-8,"extraWalkMin":6}]}
        답: {"KEEP":["4분 뒤 버스라 정류장까지 서둘러야 해요","그래도 다른 방법보다 늦지 않아요"],"DETOUR":["다른 정류장까지 6분 더 걷고 8분 늦게 도착해요"]}

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
                : templates(fact, ranked.size() == 1);

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
            bus == null ? null : shortLabel(bus.routeLabel()),
            bySubway(strategy),
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
            minutes(strategy.busArrivalAt(), strategy.nextBusArrivalAt()),
            cautionCause(strategy)
        );
    }

    // 다른 교통수단이 지하철을 타면 true, 아니면 보내지 않는다
    static Boolean bySubway(StrategyView strategy) {
        boolean subway = strategy.strategyType() == StrategyType.OTHER_MODE
            && strategy.route() != null
            && strategy.route().segments().stream()
                .anyMatch(segment -> "SUBWAY".equals(segment.mode()));

        return subway ? Boolean.TRUE : null;
    }

    // 괄호 안 설명은 뺀다 (예: "5001-1(예약.평일운행)" → "5001-1")
    static String shortLabel(String label) {
        if (label == null) {
            return null;
        }

        int bracket = label.indexOf('(');

        return (bracket > 0 ? label.substring(0, bracket) : label).trim();
    }

    /**
     * 주의·위험인 이유. 버스 구간 중 여유가 부족한 곳이 있으면 SLACK,
     * 예측 좌석이 부족한 곳이 있으면 SEAT, 둘 다 아니면 실시간 정보가 없어서(NO_INFO).
     */
    static CautionCause cautionCause(StrategyView strategy) {
        if (strategy.strategyRiskStage() == null
            || strategy.strategyRiskStage() == Stage.OK
            || strategy.analysis() == null
            || strategy.analysis().isEmpty()) {
            return null;
        }

        if (strategy.analysis().stream().anyMatch(bus -> risky(bus.slackStage()))) {
            return CautionCause.SLACK;
        }

        if (strategy.analysis().stream().anyMatch(bus ->
            bus.seatType() == SeatType.PREDICTED && risky(bus.seatStage()))) {
            return CautionCause.SEAT;
        }

        return CautionCause.NO_INFO;
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

        return parseValid(answer.get(), facts);
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

    /**
     * 전략마다 검사한다. 쓸 수 있는 숫자는 그 전략의 값과 지금 경로 비교값뿐이다
     * (다른 전략의 숫자를 섞어 쓰지 않게).
     */
    Map<StrategyType, List<String>> parseValid(String answer, List<Fact> facts) {
        Map<StrategyType, List<String>> result = new LinkedHashMap<>();
        String keepValue = facts.stream()
            .filter(fact -> fact.strategyType() == StrategyType.KEEP)
            .map(fact -> String.valueOf(fact.comparisonValueMin()))
            .findFirst()
            .orElse("");
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

            Set<String> allowed = allowedNumbers(
                jsonMapper.writeValueAsString(fact) + " " + keepValue
            );

            // 여유가 위험이면 놓칠 수 있다는 말이 있어야 한다
            boolean missWarned = fact.slackStage() != Stage.RISK
                || sentences.stream().anyMatch(sentence -> sentence.contains("놓"));

            if (missWarned
                && valid(sentences, fact.recommended(), allowed, fact.diffMin())) {
                result.put(fact.strategyType(), sentences);
            }
        }

        return result;
    }

    /**
     * diffMin: 빨라지는 분(음수면 늦어짐). 그 숫자를 쓴 문장이 빠름/늦음을 반대로 말하면 탈락.
     */
    static boolean valid(
        List<String> sentences,
        boolean recommended,
        Set<String> allowedNumbers,
        Integer diffMin
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
                || sentence.contains("퍼센트")
                || BANNED_WORDS.stream().anyMatch(sentence::contains)) {
                return false;
            }

            Matcher matcher = NUMBER.matcher(sentence);

            while (matcher.find()) {
                if (!allowedNumbers.contains(matcher.group())) {
                    return false;
                }
            }

            if (wrongDirection(sentence, diffMin)) {
                return false;
            }
        }

        return true;
    }

    private static boolean wrongDirection(String sentence, Integer diffMin) {
        if (diffMin == null || diffMin == 0
            || !sentence.contains(Math.abs(diffMin) + "분")) {
            return false;
        }

        return diffMin > 0
            ? sentence.contains("늦")
            : sentence.contains("빨리") || sentence.contains("빨라");
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
     * 빨라지는 시간이 작으면 좌석·여유를 먼저, 여유가 위험한 대안은 놓칠 수 있다는 말을 쓴다.
     */
    static List<String> templates(Fact fact, boolean alone) {
        List<String> reasons = new ArrayList<>();
        reasons.add(fact.strategyType() == StrategyType.KEEP
            ? keepSentence(fact)
            : !fact.recommended() && fact.slackStage() == Stage.RISK
                ? missRiskSentence(fact)
                : gainSentence(fact));

        if (fact.recommended()) {
            boolean riskyKeep = fact.strategyType() == StrategyType.KEEP
                && (risky(fact.slackStage()) || risky(fact.seatStage()));

            // KEEP이 위험한데 1위면 그래도 나은 이유, 혼자면 놓쳤을 때 기다릴 시간
            String extra = !riskyKeep
                ? extraSentence(fact)
                : !alone
                    ? "그래도 다른 방법보다 늦지 않아요"
                    : missSentence(fact);

            if (extra != null && !reasons.contains(extra)) {
                // 시간 차이가 작으면 좌석·여유를 먼저 말한다
                reasons.add(smallGain(fact) ? 0 : reasons.size(), extra);
            }
        }

        return reasons;
    }

    // 1위가 아닌 대안인데 여유가 위험: 빨라지는 시간과 놓칠 위험을 같이 말한다
    private static String missRiskSentence(Fact fact) {
        return fact.diffMin() != null && fact.diffMin() > 0
            ? fact.diffMin() + "분 빨라도 버스를 놓칠 수 있어요"
            : "이 방법은 버스를 놓칠 수 있어요";
    }

    private static boolean smallGain(Fact fact) {
        return fact.strategyType() != StrategyType.KEEP
            && fact.cautionCause() == null
            && (fact.diffMin() == null || fact.diffMin() <= SMALL_GAIN_MIN);
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
                case BACKTRACK -> "이전 정류장에서 탈 수 있어요";
                case ALT_ROUTE -> fit(
                    "같은 정류장에서 " + fact.routeLabel() + "번도 탈 수 있어요",
                    "같은 정류장에서 다른 노선도 탈 수 있어요"
                );
                case DETOUR -> "다른 정류장에서 타는 방법이에요";
                default -> "버스를 기다리지 않아도 돼요";
            };
        }

        if (diff < 0) {
            return switch (fact.strategyType()) {
                case OTHER_MODE -> modeName(fact) + " 가면 " + (-diff) + "분 늦게 도착해요";
                default -> "지금 경로보다 " + (-diff) + "분 늦게 도착해요";
            };
        }

        if (diff == 0) {
            return "지금 경로와 비슷하게 도착해요";
        }

        return switch (fact.strategyType()) {
            case BACKTRACK -> fact.extraWalkMin() > 0
                ? "이전 정류장까지 " + fact.extraWalkMin() + "분 더 걸으면 "
                    + diff + "분 빨리 도착해요"
                : "이전 정류장에서 타면 " + diff + "분 빨리 도착해요";
            case ALT_ROUTE -> fit(
                fact.routeLabel() + "번으로 바꾸면 " + diff + "분 빨리 도착해요",
                "다른 노선으로 바꾸면 " + diff + "분 빨리 도착해요"
            );
            case DETOUR -> "다른 정류장에서 타면 " + diff + "분 빨리 도착해요";
            default -> modeName(fact) + " 가면 " + diff + "분 빨리 도착해요";
        };
    }

    private static String modeName(Fact fact) {
        return Boolean.TRUE.equals(fact.bySubway()) ? "지하철로" : "버스 대신";
    }

    private static String missSentence(Fact fact) {
        return fact.nextBusGapMin() == null
            ? null
            : "놓치면 " + fact.nextBusGapMin() + "분 더 기다려야 해요";
    }

    // 추천 전략의 두 번째 문장: 주의 이유 → 좌석 → 여유 → (다른 교통수단) 기다림 없음
    private static String extraSentence(Fact fact) {
        if (fact.cautionCause() == CautionCause.SLACK) {
            return fact.slackStage() == Stage.RISK
                ? "지금 가면 버스를 놓칠 수 있어요"
                : "버스 도착까지 여유가 빠듯해요";
        }

        if (fact.cautionCause() == CautionCause.SEAT) {
            return "빈자리가 많지 않을 수 있어요";
        }

        if (fact.cautionCause() == CautionCause.NO_INFO) {
            return "실시간 정보가 부족해 주의가 필요해요";
        }

        if (fact.seatType() == SeatType.PREDICTED && fact.seatStage() == Stage.OK) {
            return "빈자리가 넉넉할 것으로 예상돼요";
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
     * LLM에 보내는 전략 하나의 사실. 위치 좌표·주소·기기 ID·정류장 이름은 넣지 않는다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Fact(
        StrategyType strategyType,
        boolean recommended,
        String routeLabel,
        Boolean bySubway,
        Integer comparisonValueMin,
        Integer diffMin,
        int extraWalkMin,
        Stage strategyRiskStage,
        Stage slackStage,
        Stage seatStage,
        Integer remainSeats,
        SeatType seatType,
        Integer waitMin,
        Integer nextBusGapMin,
        CautionCause cautionCause
    ) {
    }

    enum CautionCause {
        SLACK,
        SEAT,
        NO_INFO
    }
}
