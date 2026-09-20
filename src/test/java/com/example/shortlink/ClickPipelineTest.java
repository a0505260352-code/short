package com.example.shortlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shortlink.mq.ClickBatchWriter;
import com.example.shortlink.mq.ClickEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The click pipeline end to end: redirect, broker, batched writer, database, counter.
 *
 * <p>Gated on {@code SHORTLINK_MQ_TEST=true} because it needs a running broker, which Testcontainers has
 * no official module for. Point it at the compose stack in {@code docker/}; the topic and the consumer
 * group are renamed per run so this cannot steal messages from a development instance's group.
 *
 * <p>The first thing it does is prove the consumer is online by landing one click. A brand-new consumer
 * group starts at the tail of the queue and discovers an auto-created topic asynchronously, so a click
 * published before that would simply be skipped — without the warm-up every assertion below would be
 * racing the route table instead of the behaviour under test.
 */
@EnabledIfEnvironmentVariable(named = "SHORTLINK_MQ_TEST", matches = "true")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.cloud.stream.bindings.click-out-0.destination=short-link-click-it",
        "spring.cloud.stream.bindings.clickConsumer-in-0.destination=short-link-click-it",
        "spring.cloud.stream.bindings.clickConsumer-in-0.consumer.group=shortlink-click-it-group"})
class ClickPipelineTest {

    private static final String TARGET = "https://example.com/report/quarterly";
    private static final Duration WARM_UP = Duration.ofSeconds(90);
    private static final Duration AFTER_WARM_UP = Duration.ofSeconds(15);

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("shortlink")
            .withUsername("shortlink")
            .withPassword("shortlink");

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ClickBatchWriter writer;

    private String createLink() throws Exception {
        String body = mvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + TARGET + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("code").asText();
    }

    private void redirect(String code, String userAgent) throws Exception {
        mvc.perform(get("/" + code).header("User-Agent", userAgent)).andExpect(status().isFound());
    }

    private int rowsFor(String code) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_link_click_log WHERE code = ?", Integer.class, code);
        return count == null ? 0 : count;
    }

    private void awaitRows(String code, int expected, Duration timeout) {
        awaitTrue(() -> rowsFor(code) >= expected, timeout,
                () -> "click rows for " + code + " to reach " + expected + ", found " + rowsFor(code));
    }

    private static void awaitTrue(BooleanSupplier condition, Duration timeout, Supplier<String> description) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out after " + timeout + ": " + description.get());
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void redirectsLandAsRowsAndTheCounterAgreesWithThem() throws Exception {
        String warmUp = createLink();
        redirect(warmUp, "warm-up");
        awaitRows(warmUp, 1, WARM_UP);

        String code = createLink();
        for (int i = 0; i < 3; i++) {
            redirect(code, "visitor-" + i);
        }
        awaitRows(code, 3, AFTER_WARM_UP);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT event_id, INET6_NTOA(client_ip) AS ip, user_agent, referer, click_time"
                        + " FROM t_link_click_log WHERE code = ? ORDER BY user_agent", code);
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> {
            assertThat((String) row.get("event_id")).isNotBlank();
            assertThat((String) row.get("ip")).isNotBlank();
            assertThat(row.get("click_time")).isNotNull();
        });
        assertThat(rows).extracting(row -> (String) row.get("user_agent"))
                .containsExactly("visitor-0", "visitor-1", "visitor-2");

        assertThat(counterOf(code)).isEqualTo(3);
    }

    @Test
    void aReplayedEventAddsNeitherARowNorACount() throws Exception {
        String warmUp = createLink();
        redirect(warmUp, "warm-up");
        awaitRows(warmUp, 1, WARM_UP);

        String code = createLink();
        redirect(code, "only-visitor");
        awaitRows(code, 1, AFTER_WARM_UP);
        // Read the stored instant back rather than re-deriving it: click_time is part of uk_event, so a
        // replay must carry the exact value the first attempt wrote to be absorbed the way a redelivery is.
        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT event_id, click_time FROM t_link_click_log WHERE code = ?", code);
        long countAfterFirst = counterOf(code);
        LocalDateTime clickTime = (LocalDateTime) stored.get("click_time");

        // Handing the same event straight to the writer is what a broker redelivery looks like from here.
        writer.accept(new ClickEvent((String) stored.get("event_id"), code,
                clickTime.toInstant(ZoneOffset.UTC).toEpochMilli(), "203.0.113.9", "replay", null));

        // Well past one flush interval, so the replay has had every chance to be written.
        Thread.sleep(2_000);
        assertThat(rowsFor(code)).isEqualTo(1);
        assertThat(counterOf(code)).isEqualTo(countAfterFirst);
    }

    private long counterOf(String code) {
        Long count = jdbc.queryForObject("SELECT click_count FROM t_short_link WHERE code = ?", Long.class,
                code);
        return count == null ? 0L : count;
    }
}
