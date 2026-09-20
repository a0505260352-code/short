package com.example.shortlink.maintenance;

import com.example.shortlink.persistence.mapper.ShortLinkMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Moves rows whose {@code valid_until} has passed from ACTIVE to EXPIRED.
 *
 * <p>This is bookkeeping, not enforcement — and the distinction is why it can be a slow sweep instead of
 * a per-link scheduled message. Nothing on the redirect path ever trusts {@code status} alone:
 * {@code ShortLinkService} refuses a link whose {@code valid_until} is in the past even while the row
 * still reads ACTIVE, and the cache entry for such a link is clamped to that same instant, so it cannot
 * outlive the window either. A link therefore stops forwarding at its expiry date to the millisecond,
 * and the only thing that lags by up to a sweep interval is the {@code status} field the stats API
 * reports. Paying a delay-message ladder per link — hundreds of re-injected messages for a 30-day link,
 * since the broker's longest delay level is hours — to close a lag nobody reads is a bad trade, so the
 * ladder is not implemented and this sweep is the whole mechanism.
 *
 * <p>Every instance runs its own sweep. That is harmless rather than merely tolerated: rows leave the
 * predicate as soon as they are updated, so two overlapping sweeps cannot flip the same row twice or
 * disagree about the outcome.
 */
@Component
public class ExpirySweepTask {

    private static final Logger log = LoggerFactory.getLogger(ExpirySweepTask.class);

    private final ShortLinkMapper mapper;
    private final ExpiryProperties props;
    private final Clock clock;

    public ExpirySweepTask(ShortLinkMapper mapper, ExpiryProperties props, Clock clock) {
        this.mapper = mapper;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${shortlink.expiry.sweep-interval:10m}")
    public void sweep() {
        int flipped = sweepOnce();
        if (flipped > 0) {
            log.info("Expired {} link(s)", flipped);
        }
    }

    /**
     * Drains the backlog in bounded batches, so a sweep that wakes after a long outage cannot take out
     * row locks across every due link in one statement.
     *
     * @return rows moved to EXPIRED
     */
    public int sweepOnce() {
        LocalDateTime cutoff = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int batch = props.sweepBatchSize();
        int total = 0;
        int flipped;
        do {
            flipped = mapper.expireDueBefore(cutoff, batch);
            total += flipped;
        } while (flipped == batch);
        return total;
    }
}
