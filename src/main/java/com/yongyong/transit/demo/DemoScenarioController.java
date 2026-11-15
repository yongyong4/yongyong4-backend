
package com.yongyong.transit.demo;

import com.yongyong.transit.analysis.service.SegmentAnalyzer;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.BusArrival;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SeatType;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SegmentInput;
import com.yongyong.transit.analysis.service.SegmentAnalyzer.SegmentResult;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@Profile("demo")
@RequestMapping("/api/v1/demo")
public class DemoScenarioController {

    private final DemoClock clock;
    private final SegmentAnalyzer analyzer;
    private final DemoScenarioData scenarioData;

    public DemoScenarioController(
        DemoClock clock,
        SegmentAnalyzer analyzer,
        DemoScenarioData scenarioData
    ) {
        this.clock = clock;
        this.analyzer = analyzer;
        this.scenarioData = scenarioData;
    }

    @GetMapping("/clock")
    public Map<String, Object> getClock() {
        return clockResponse();
    }

    @PostMapping("/clock/reset")
    public Map<String, Object> resetClock() {
        clock.reset();
        return clockResponse();
    }

    @PostMapping("/clock/set")
    public Map<String, Object> setClock(
        @RequestParam String at
    ) {
        try {
            clock.set(OffsetDateTime.parse(at));
        } catch (
            DateTimeParseException
            | IllegalArgumentException e
        ) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "올바른 ISO 8601 시각을 입력해주세요."
            );
        }

        return clockResponse();
    }

    @PostMapping("/clock/advance")
    public Map<String, Object> advanceClock(
        @RequestParam long minutes
    ) {
        try {
            clock.advance(minutes);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                e.getMessage()
            );
        }

        return clockResponse();
    }

    @GetMapping("/preview")
    public Map<String, Object> preview() {
        OffsetDateTime now = clock.now();

        List<BusArrival> buses = scenarioData.buses(now)
            .stream()
            .filter(bus -> DemoScenarioData.BUS_5003B_ID.equals(bus.routeId()))
            .toList();

        SegmentInput input = new SegmentInput(
            2,
            "5003B",
            "동백역.성산마을서해그랑블",
            now,
            2,
            66,
            7,
            true,
            buses,
            now,
            false
        );

        SegmentResult result = analyzer.analyze(input);

        Map<String, Object> response =
            new LinkedHashMap<>();

        response.put("demo", true);
        response.put("virtualNow", now);
        response.put("routeId", "228000182");
        response.put("stationId", "228000172");
        response.put("routeLabel", "5003B");
        response.put("analysis", result);

        return response;
    }

    private Map<String, Object> clockResponse() {
        Map<String, Object> response =
            new LinkedHashMap<>();

        response.put("demo", true);
        response.put("virtualNow", clock.now());

        return response;
    }
}
