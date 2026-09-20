package com.example.shortlink.maintenance;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the RANGE partitions of {@code t_link_click_log} ahead of the clock, and drops the ones behind
 * the retention window.
 *
 * <p>Expiry here is a partition drop, never a DELETE: dropping detaches a filesystem object and returns
 * instantly, while {@code DELETE WHERE click_time < ?} over millions of rows locks the table it is
 * meant to shrink and leaves the space unusable. That only works if every day has its own partition,
 * which is what the horizon guarantees — and why it must stay ahead of the data, because splitting a
 * partition that already holds rows rewrites them.
 *
 * <p>Runs on every start as well as daily, so an instance that was down over its scheduled time does
 * not wake up to a backlog in {@code pmax}. Several instances starting together race on the same DDL;
 * the loser logs a failure and the next daily run catches up, which is why nothing here is fatal.
 */
@Component
public class PartitionMaintenanceTask {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceTask.class);
    private static final String TABLE = "t_link_click_log";
    private static final String MAX_PARTITION = "pmax";
    private static final String BOOTSTRAP_PARTITION = "p_bootstrap";
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern DAY_PARTITION = Pattern.compile("p_\\d{8}");

    private final JdbcTemplate jdbc;
    private final PartitionProperties props;
    private final Clock clock;

    public PartitionMaintenanceTask(JdbcTemplate jdbc, PartitionProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "0 30 2 * * *", zone = "UTC")
    public void maintain() {
        try {
            maintain(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
        } catch (RuntimeException e) {
            // Never let housekeeping take a working instance down: a missed day costs storage, not traffic.
            log.error("{} partition maintenance failed; the next scheduled run retries", TABLE, e);
        }
    }

    void maintain(LocalDate today) {
        Set<String> present = currentPartitions();
        createAhead(today, present);
        dropBefore(today.minusDays(props.retentionDays()), present);
    }

    private void createAhead(LocalDate today, Set<String> present) {
        LocalDate last = today.plusDays(props.horizonDays());
        for (LocalDate day = today.plusDays(1); !day.isAfter(last); day = day.plusDays(1)) {
            String name = dayPartition(day);
            if (present.contains(name)) {
                continue;
            }
            // Generated from a LocalDate, so the only shapes it can take are the ones MySQL accepts.
            String exclusiveBound = day.plusDays(1).toString();
            log.info("Adding partition {} less than {} to {}", name, exclusiveBound, TABLE);
            jdbc.execute("ALTER TABLE " + TABLE + " REORGANIZE PARTITION " + MAX_PARTITION + " INTO (PARTITION "
                    + name + " VALUES LESS THAN (TO_DAYS('" + exclusiveBound + "')), PARTITION " + MAX_PARTITION
                    + " VALUES LESS THAN MAXVALUE)");
            present.add(name);
        }
    }

    private void dropBefore(LocalDate cutoff, Set<String> present) {
        boolean anythingExpired = false;
        // Day partitions are fixed width, so their names order exactly like the dates they hold.
        String cutoffName = dayPartition(cutoff);
        for (String name : present) {
            if (DAY_PARTITION.matcher(name).matches() && name.compareTo(cutoffName) < 0) {
                drop(name);
                anythingExpired = true;
            }
        }
        // p_bootstrap holds everything before the first day partition, so it is by construction the
        // oldest data in the table and leaves with the first day that passes retention.
        if (anythingExpired && present.contains(BOOTSTRAP_PARTITION)) {
            drop(BOOTSTRAP_PARTITION);
        }
    }

    private void drop(String name) {
        log.info("Dropping expired partition {} from {}", name, TABLE);
        jdbc.execute("ALTER TABLE " + TABLE + " DROP PARTITION " + name);
    }

    private Set<String> currentPartitions() {
        return new HashSet<>(jdbc.queryForList(
                "SELECT PARTITION_NAME FROM INFORMATION_SCHEMA.PARTITIONS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND PARTITION_NAME IS NOT NULL",
                String.class, TABLE));
    }

    private static String dayPartition(LocalDate day) {
        return "p_" + BASIC_DATE.format(day);
    }
}
