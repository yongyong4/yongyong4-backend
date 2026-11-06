
-- 용용톤 서비스 DB
-- 이동 세션, 이동 경로, 상태 변경, 재평가, 종료 기록
-- MySQL / MariaDB 기준
-- 새 서비스 DB에서 최초 1회 실행

CREATE TABLE IF NOT EXISTS device (
                                      device_id VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (device_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS trip (
                                    trip_id BIGINT NOT NULL AUTO_INCREMENT,
                                    device_id VARCHAR(64) NOT NULL,
    current_route_id BIGINT NULL,
    current_status VARCHAR(20) NOT NULL,
    dest_name VARCHAR(200) NOT NULL,
    started_at DATETIME NOT NULL,
    ended_at DATETIME NULL,
    end_type VARCHAR(20) NULL,
    near_dest_at DATETIME NULL,
    expected_arrival_at DATETIME NULL,
    last_suggested_at DATETIME NULL,
    summary_shown BOOLEAN NOT NULL DEFAULT FALSE,
    alt_routes_json JSON NULL,

    PRIMARY KEY (trip_id),
    INDEX idx_trip_device_active (device_id, ended_at),

    CONSTRAINT fk_trip_device
    FOREIGN KEY (device_id)
    REFERENCES device(device_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS trip_route (
                                          trip_route_id BIGINT NOT NULL AUTO_INCREMENT,
                                          trip_id BIGINT NOT NULL,
                                          source VARCHAR(20) NOT NULL,
    evaluation_id BIGINT NULL,
    strategy_type VARCHAR(20) NOT NULL,
    created_at DATETIME NOT NULL,

    PRIMARY KEY (trip_route_id),
    INDEX idx_trip_route_trip (trip_id, created_at),

    CONSTRAINT fk_trip_route_trip
    FOREIGN KEY (trip_id)
    REFERENCES trip(trip_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS segment (
                                       segment_id BIGINT NOT NULL AUTO_INCREMENT,
                                       trip_route_id BIGINT NOT NULL,
                                       seq INT NOT NULL,
                                       mode VARCHAR(20) NOT NULL,
    gbis_route_id VARCHAR(20) NULL,
    route_label VARCHAR(50) NULL,
    board_station_id VARCHAR(20) NULL,
    alight_station_id VARCHAR(20) NULL,
    board_name VARCHAR(100) NULL,
    alight_name VARCHAR(100) NULL,
    match_status VARCHAR(20) NOT NULL,
    planned_start_at DATETIME NULL,
    planned_end_at DATETIME NULL,
    walk_min INT NULL,
    user_alight_at DATETIME NULL,

    PRIMARY KEY (segment_id),
    UNIQUE KEY uk_segment_route_seq (trip_route_id, seq),

    CONSTRAINT fk_segment_trip_route
    FOREIGN KEY (trip_route_id)
    REFERENCES trip_route(trip_route_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS trip_status_log (
                                               log_id BIGINT NOT NULL AUTO_INCREMENT,
                                               trip_id BIGINT NOT NULL,
                                               segment_id BIGINT NULL,
                                               status VARCHAR(20) NOT NULL,
    plate_no VARCHAR(20) NULL,
    changed_at DATETIME NOT NULL,

    PRIMARY KEY (log_id),
    INDEX idx_trip_status_log_trip (trip_id, changed_at),

    CONSTRAINT fk_status_log_trip
    FOREIGN KEY (trip_id)
    REFERENCES trip(trip_id),

    CONSTRAINT fk_status_log_segment
    FOREIGN KEY (segment_id)
    REFERENCES segment(segment_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS evaluation (
                                          evaluation_id BIGINT NOT NULL AUTO_INCREMENT,
                                          trip_id BIGINT NOT NULL,
                                          trigger_type VARCHAR(20) NOT NULL,
    evaluated_at DATETIME NOT NULL,
    data_basis_at DATETIME NULL,
    result VARCHAR(20) NOT NULL,
    suggestion_json JSON NULL,
    accepted BOOLEAN NULL,
    responded_at DATETIME NULL,
    target_segment_id BIGINT NULL,
    pred_plate_no VARCHAR(20) NULL,
    pred_arrival_at DATETIME NULL,
    pred_seat INT NULL,
    slack_stage VARCHAR(20) NULL,
    seat_stage VARCHAR(20) NULL,
    model_version VARCHAR(50) NULL,
    suggested_strategy VARCHAR(20) NULL,
    current_value_min INT NULL,
    suggested_value_min INT NULL,
    latency_ms INT NULL,

    PRIMARY KEY (evaluation_id),
    INDEX idx_evaluation_trip (trip_id, evaluated_at),

    CONSTRAINT fk_evaluation_trip
    FOREIGN KEY (trip_id)
    REFERENCES trip(trip_id),

    CONSTRAINT fk_evaluation_segment
    FOREIGN KEY (target_segment_id)
    REFERENCES segment(segment_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 순환 참조 관계는 테이블 생성 후 연결
ALTER TABLE trip
    ADD CONSTRAINT fk_trip_current_route
        FOREIGN KEY (current_route_id)
            REFERENCES trip_route(trip_route_id);

ALTER TABLE trip_route
    ADD CONSTRAINT fk_trip_route_evaluation
        FOREIGN KEY (evaluation_id)
            REFERENCES evaluation(evaluation_id);
