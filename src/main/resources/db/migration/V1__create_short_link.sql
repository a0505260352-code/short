-- Short link master table.
--
-- The `code` column deliberately overrides the table default collation.
-- utf8mb4_0900_ai_ci is case-insensitive, which would make 'aB3dE7' and 'AB3de7' equal:
-- that silently breaks uk_code as a uniqueness guarantee AND makes the redirect lookup
-- able to return the wrong row. Base62 codes are pure ASCII, so ascii_bin is both correct
-- and cheaper per index byte.

CREATE TABLE t_short_link (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    original_url  TEXT            NOT NULL,
    code_type     TINYINT         NOT NULL DEFAULT 0 COMMENT '0=HASH (fixed 6 chars) 1=VANITY (user supplied)',
    status        TINYINT         NOT NULL DEFAULT 1 COMMENT '1=ACTIVE 2=EXPIRED 3=DELETED',
    valid_from    DATETIME(3)     NULL,
    valid_until   DATETIME(3)     NULL COMMENT 'NULL means never expires',
    owner_id      BIGINT UNSIGNED NULL COMMENT 'Reserved for the auth layer; always NULL in v1',
    click_count   BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code),
    KEY idx_expiry_sweep (status, valid_until)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
