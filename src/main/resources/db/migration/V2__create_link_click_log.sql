-- Click log, written only by the async batch consumer. Never written on the redirect path.
--
-- Every unique key must contain the partition column: MySQL/InnoDB rejects a partitioning
-- scheme whose global uniqueness it cannot enforce per partition. That is why the primary key
-- is (id, click_time) rather than (id).
--
-- Idempotency still holds because click_time is stamped once by the producer and is preserved
-- across RocketMQ redelivery, so a duplicate event always lands in the same partition and is
-- absorbed by INSERT IGNORE against uk_event.

CREATE TABLE t_link_click_log (
    id         BIGINT UNSIGNED           NOT NULL AUTO_INCREMENT,
    event_id   CHAR(36)                  NOT NULL COMMENT 'Producer-generated UUID, dedup key',
    code       VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    click_time DATETIME(3)               NOT NULL COMMENT 'When the click happened, not when it was stored',
    client_ip  VARBINARY(16)             NULL COMMENT 'INET6_ATON output; holds IPv4 and IPv6',
    user_agent VARCHAR(512)              NULL,
    referer    VARCHAR(1024)             NULL,
    PRIMARY KEY (id, click_time),
    UNIQUE KEY uk_event (event_id, click_time),
    KEY idx_code_time (code, click_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
PARTITION BY RANGE (TO_DAYS(click_time)) (
    PARTITION p_bootstrap VALUES LESS THAN (TO_DAYS('2026-09-21')),
    PARTITION pmax        VALUES LESS THAN MAXVALUE
);
