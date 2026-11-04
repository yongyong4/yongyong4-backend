
package com.yongyong.transit.collector.service;

import com.yongyong.transit.collector.client.CollectorClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Service
public class CollectorQueryService {

    private static final long FRESHNESS_SECONDS = 240;

    private final CollectorClient collectorClient;

    public CollectorQueryService(
        CollectorClient collectorClient
    ) {
        this.collectorClient = collectorClient;
    }

    public SegmentSnapshot getSegmentSnapshot(
        String routeId,
        String stationId
    ) {
        JsonNode routes = requireArray(
            collectorClient.getRoutes(),
            "routes"
        );

        String routeName = findRouteName(routes, routeId);

        JsonNode locations = requireArray(
            collectorClient.getLocations(),
            "locations"
        );

        JsonNode arrivals = requireArray(
            collectorClient.getArrivals(),
            "arrivals"
        );

        Instant now = Instant.now();

        List<Vehicle> vehicles = new ArrayList<>();

        for (JsonNode item : locations) {
            if (!routeId.equals(text(item, "route_id"))) {
                continue;
            }

            String collectedAt = text(item, "collected_at");

            vehicles.add(new Vehicle(
                text(item, "plate_no"),
                integer(item, "station_seq"),
                text(item, "station_name"),
                integer(item, "remain_seat_cnt"),
                collectedAt,
                isFresh(collectedAt, now)
            ));
        }

        List<Arrival> matchedArrivals = new ArrayList<>();

        for (JsonNode item : arrivals) {
            boolean sameRoute = routeName.equals(
                text(item, "route_name")
            );

            boolean sameStation = stationId.equals(
                text(item, "station_id")
            );

            if (!sameRoute || !sameStation) {
                continue;
            }

            String collectedAt = text(item, "collected_at");

            matchedArrivals.add(new Arrival(
                text(item, "station_name"),
                integer(item, "arrival_order"),
                text(item, "plate_no"),
                integer(item, "predict_time_min"),
                integer(item, "remain_seat_cnt"),
                collectedAt,
                isFresh(collectedAt, now)
            ));
        }

        boolean locationsFresh = !vehicles.isEmpty()
            && vehicles.stream().allMatch(Vehicle::fresh);

        boolean arrivalsFresh = !matchedArrivals.isEmpty()
            && matchedArrivals.stream().allMatch(Arrival::fresh);

        boolean readyForLiveAnalysis =
            locationsFresh && arrivalsFresh;

        return new SegmentSnapshot(
            "COLLECTOR_API",
            routeId,
            routeName,
            stationId,
            locationsFresh,
            arrivalsFresh,
            readyForLiveAnalysis,
            vehicles,
            matchedArrivals
        );
    }

    private JsonNode requireArray(
        JsonNode value,
        String name
    ) {
        if (value == null || !value.isArray()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Invalid collector " + name + " response"
            );
        }

        return value;
    }

    private String findRouteName(
        JsonNode routes,
        String routeId
    ) {
        for (JsonNode route : routes) {
            if (routeId.equals(text(route, "route_id"))) {
                return text(route, "route_name");
            }
        }

        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "Route is not included in collector"
        );
    }

    private String text(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.path(field);

        if (value.isMissingNode() || value.isNull()) {
            return "";
        }

        return value.asText();
    }

    private Integer integer(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.path(field);

        if (value.isMissingNode() || value.isNull()) {
            return null;
        }

        return value.asInt();
    }

    private boolean isFresh(
        String collectedAt,
        Instant now
    ) {
        if (collectedAt == null || collectedAt.isBlank()) {
            return false;
        }

        try {
            Instant collectedTime = OffsetDateTime
                .parse(collectedAt)
                .toInstant();

            Instant cutoff = now.minusSeconds(
                FRESHNESS_SECONDS
            );

            Instant futureTolerance = now.plusSeconds(60);

            return collectedTime.isAfter(cutoff)
                && !collectedTime.isAfter(futureTolerance);

        } catch (DateTimeParseException e) {
            return false;
        }
    }

    public record Vehicle(
        String plateNo,
        Integer stationSeq,
        String stationName,
        Integer remainSeatCnt,
        String collectedAt,
        boolean fresh
    ) {
    }

    public record Arrival(
        String stationName,
        Integer arrivalOrder,
        String plateNo,
        Integer predictTimeMin,
        Integer remainSeatCnt,
        String collectedAt,
        boolean fresh
    ) {
    }

    public record SegmentSnapshot(
        String source,
        String routeId,
        String routeName,
        String stationId,
        boolean locationsFresh,
        boolean arrivalsFresh,
        boolean readyForLiveAnalysis,
        List<Vehicle> vehicles,
        List<Arrival> arrivals
    ) {
    }
}
