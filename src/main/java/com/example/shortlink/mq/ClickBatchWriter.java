package com.example.shortlink.mq;

import com.example.shortlink.persistence.entity.LinkClickLogEntity;
import com.example.shortlink.persistence.mapper.LinkClickLogMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Holds clicks in memory so the database is written in batches instead of once per redirect.
 *
 * <p><b>Backpressure, not loss.</b> {@link #accept} blocks while the queue is full. A consumer thread
 * parked here stops the RocketMQ listener pool, which stops the client pulling, which leaves the
 * backlog on the broker — where it survives a database outage and drains afterwards. The alternative
 * of failing fast on a full queue would discard exactly the clicks that arrive while the system is
 * already struggling.
 *
 * <p><b>What can still be lost, and why that is accepted.</b> v1 treats click counts as analytics, not
 * accounting, so three windows stay open and each is logged when it happens: a flush that fails and
 * rolls back, a queue wedged past {@code offer-timeout-ms}, and everything still buffered when the
 * process dies outside a graceful shutdown — which is up to {@code queue-capacity} events, not one
 * batch. The redirect path never touches this class, so none of them can turn a working link into a
 * failed request.
 *
 * <p>Rows and counters commit together per batch, which is what keeps {@code click_count} from drifting
 * ahead of the rows it summarises when a redelivery is absorbed by {@code INSERT IGNORE}.
 */
@Component
public class ClickBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(ClickBatchWriter.class);

    private final BlockingQueue<ClickEvent> queue;
    private final ClickProperties props;
    private final LinkClickLogMapper mapper;
    private final TransactionTemplate txTemplate;
    private volatile boolean running = true;
    private Thread worker;

    public ClickBatchWriter(ClickProperties props, LinkClickLogMapper mapper, TransactionTemplate txTemplate) {
        this.props = props;
        this.mapper = mapper;
        this.txTemplate = txTemplate;
        this.queue = new ArrayBlockingQueue<>(props.queueCapacity());
    }

    @PostConstruct
    void start() {
        worker = new Thread(this::run, "click-flusher");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        try {
            worker.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!queue.isEmpty()) {
            log.warn("Shutdown left {} click events unwritten", queue.size());
        }
    }

    public void accept(ClickEvent event) {
        try {
            if (!queue.offer(event, props.offerTimeoutMs(), TimeUnit.MILLISECONDS)) {
                log.error("Click queue stayed full for {}ms, dropping event {}", props.offerTimeoutMs(),
                        event.eventId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        try {
            while (running || !queue.isEmpty()) {
                ClickEvent first = queue.poll(running ? props.flushIntervalMs() : 0L, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<ClickEvent> batch = new ArrayList<>(props.maxBatchRows());
                batch.add(first);
                queue.drainTo(batch, props.maxBatchRows() - 1);
                write(batch);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Click flusher stopped with {} events unwritten", queue.size());
        }
    }

    private void write(List<ClickEvent> batch) {
        Map<String, List<LinkClickLogEntity>> byCode = new LinkedHashMap<>();
        for (ClickEvent event : batch) {
            byCode.computeIfAbsent(event.code(), k -> new ArrayList<>()).add(rowOf(event));
        }
        try {
            Integer inserted = txTemplate.execute(status -> {
                int total = 0;
                for (Map.Entry<String, List<LinkClickLogEntity>> group : byCode.entrySet()) {
                    int rows = mapper.insertIgnore(group.getValue());
                    if (rows > 0) {
                        mapper.addClickCount(group.getKey(), rows);
                    }
                    total += rows;
                }
                return total;
            });
            if (inserted != null && inserted < batch.size()) {
                log.info("Absorbed {} replayed click events in a batch of {}", batch.size() - inserted,
                        batch.size());
            }
        } catch (RuntimeException e) {
            log.error("Click batch of {} events failed to commit and is lost", batch.size(), e);
        }
    }

    private static LinkClickLogEntity rowOf(ClickEvent event) {
        LinkClickLogEntity row = new LinkClickLogEntity();
        row.setEventId(event.eventId());
        row.setCode(event.code());
        row.setClickTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(event.occurredAt()), ZoneOffset.UTC));
        row.setClientIp(event.clientIp());
        row.setUserAgent(event.userAgent());
        row.setReferer(event.referer());
        return row;
    }
}
