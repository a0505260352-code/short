package com.example.shortlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shortlink.bloom.BloomService;
import com.example.shortlink.maintenance.ExpirySweepTask;
import com.example.shortlink.persistence.entity.ShortLinkEntity;
import com.example.shortlink.persistence.mapper.ShortLinkMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End to end over a real MySQL — so Flyway builds the real schema, {@code ascii_bin} included — and a
 * real Redis, so the bloom filter and the redirect cache behave as they will in production.
 *
 * <p>MockMvc rather than a live HTTP client on purpose: the assertion that matters on the redirect path
 * is the status plus the {@code Location} header, and a real client would follow the 302 out to the
 * public internet and make the suite depend on it.
 *
 * <p>The click pipeline is pointed at a dead port here, which is also an assertion: every test below
 * redirects through the code that publishes a click event, so if that path ever learned to fail a
 * request these tests would fail with it. The real pipeline gets its own test against a live broker.
 */
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.cloud.stream.bindings.clickConsumer-in-0.consumer.auto-startup=false",
        "spring.cloud.stream.rocketmq.binder.name-server=127.0.0.1:1",
        // The expiry test drives the sweep itself; a scheduled run firing while another test inspects a
        // due row would change that row's status underneath it.
        "shortlink.expiry.sweep-interval=1d"})
class ShortLinkApiTest {

    private static final String TARGET = "https://example.com/a/very/long/path?utm_source=campaign";
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

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
    private ShortLinkMapper mapper;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private BloomService bloom;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private Clock clock;

    @Autowired
    private ExpirySweepTask sweep;

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private String createLink(String extraFields) throws Exception {
        return mvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + TARGET + "\"" + extraFields + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private String codeIn(String responseBody) throws Exception {
        return json.readTree(responseBody).path("code").asText();
    }

    @Test
    void createdLinkRedirectsWith302ToTheStoredTarget() throws Exception {
        String body = createLink("");
        String code = codeIn(body);

        assertThat(body).contains("\"codeType\":\"HASH\"").contains("\"status\":\"ACTIVE\"");
        assertThat(code).hasSize(6);

        mvc.perform(get("/" + code))
                // 302, never 301: browsers cache a permanent redirect and every click after the first is lost.
                .andExpect(status().isFound())
                .andExpect(header().string("Location", TARGET));
    }

    @Test
    void unknownCodeIsNotFoundAndLeavesASentinelSoGarbageCannotFloodTheDatabase() throws Exception {
        mvc.perform(get("/nope12")).andExpect(status().isNotFound());
        assertThat(redis.opsForValue().get("sl:c:nope12")).isEmpty();

        mvc.perform(get("/nope12")).andExpect(status().isNotFound());
    }

    @Test
    void vanityCodeIsHonouredVerbatimAndAConflictIsAConflict() throws Exception {
        // "brandx" keeps the shortest candidate at 7 characters: a 6-character code belongs to the hash
        // family and the policy would reject it before any insert.
        String wanted = "brandx" + Math.abs(this.hashCode() % 10000);
        createLink(",\"code\":\"" + wanted + "\"");

        mvc.perform(get("/" + wanted))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", TARGET));

        mvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + TARGET + "\",\"code\":\"" + wanted + "\"}"))
                .andExpect(status().isConflict());

        // Every issued code joins the filter, vanity included: an absent entry there is a code the hash
        // ring would be allowed to hand out a second time.
        assertThat(bloom.possiblyExists(wanted)).isTrue();
    }

    @Test
    void malformedOrReservedVanityCodesNeverReachTheDatabase() throws Exception {
        // abcdef is rejected for length, not for a collision: six characters belong to the hash family.
        for (String rejected : new String[] {"abcdef", "api", "short!", "ab", "z".repeat(13)}) {
            mvc.perform(post("/api/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"" + TARGET + "\",\"code\":\"" + rejected + "\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void codesMatchCaseExactlyBecauseTheColumnIsBinaryCollated() throws Exception {
        String mixedCase = "aB3dE7";
        insertDirectly(mixedCase, null);

        mvc.perform(get("/" + mixedCase)).andExpect(status().isFound());
        // Under the table's default utf8mb4_0900_ai_ci this second lookup would hit the same row.
        mvc.perform(get("/AB3dE7")).andExpect(status().isNotFound());
    }

    @Test
    void clickLogIsPartitionedAheadOfTodaySoExpiryStaysAPartitionDrop() {
        // The daily horizon job runs on startup as well as on a schedule, so a fresh schema here must
        // already be able to hold tomorrow's clicks in its own partition, with pmax left as the catch-all.
        String tomorrow = "p_" + BASIC_DATE.format(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(1));

        assertThat(partitions()).contains(tomorrow, "pmax");
    }

    @Test
    void expiredLinkStopsRedirectingAndItsCacheEntryDiesWithIt() throws Exception {
        String code = "0d4y1n";
        LocalDateTime nearlyOver = utcNow().plusSeconds(2);
        insertDirectly(code, nearlyOver);

        mvc.perform(get("/" + code)).andExpect(status().isFound());
        // The cached copy was written to live no longer than the link itself, so expiring needs no
        // invalidation traffic of any kind.
        assertThat(redis.getExpire("sl:c:" + code, TimeUnit.SECONDS)).isBetween(0L, 3L);

        Thread.sleep(3_000);

        mvc.perform(get("/" + code)).andExpect(status().isNotFound());
        // The target is gone from the cache; what took its place is the known-absent marker.
        assertThat(redis.opsForValue().get("sl:c:" + code)).isEmpty();
    }

    @Test
    void targetsThatAreNotPublicHttpUrlsAreRejectedBeforeAnyRowExists() throws Exception {
        for (String url : new String[] {
                "http://127.0.0.1:9876/admin",
                "http://localhost/x",
                "http://169.254.169.254/latest/meta-data/",
                "http://[::1]/x",
                "http://100.64.0.1/x",
                "http://[fd00::1]/x",
                "ftp://example.com/file",
                "https://user:pass@example.com/x",
                "not a url at all",
                // Past the validator's own ceiling, which sits inside the DTO's 2048-char limit, so this
                // is the validator refusing rather than bean validation.
                "https://example.com/" + "a".repeat(2_000),
        }) {
            mvc.perform(post("/api/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"" + url.replace("\"", "") + "\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void statsReportsTheRowAsStored() throws Exception {
        String code = codeIn(createLink(""));
        mvc.perform(get("/api/links/" + code + "/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.clickCount").value(0))
                .andExpect(jsonPath("$.originalUrl").value(TARGET))
                .andExpect(jsonPath("$.validUntil").doesNotExist());
    }

    @Test
    void aDeadlineInTheFutureIsKeptAndOneInThePastIsRejected() throws Exception {
        Instant deadline = Instant.ofEpochSecond(utcNow().plusDays(1).toEpochSecond(ZoneOffset.UTC));
        String code = codeIn(createLink(",\"validUntil\":\"" + deadline + "\""));
        mvc.perform(get("/api/links/" + code + "/stats"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.validUntil").value(deadline.toString()));

        mvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + TARGET + "\",\"validUntil\":\"" + deadline.minusSeconds(86_400) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * The point of the sweep is narrow, and this test is where that narrowness is pinned: a link whose
     * window has closed already stops redirecting on its own, so the only thing the sweep is paid for is
     * making {@code status} honest to the caller who reads it.
     */
    @Test
    void theExpirySweepFlipsDueRowsAndLeavesEveryOtherRowAlone() throws Exception {
        String due = "0d4y1d";
        String notDue = "0d4y1f";
        insertDirectly(due, utcNow().minusHours(1));
        insertDirectly(notDue, utcNow().plusDays(1));

        mvc.perform(get("/" + due)).andExpect(status().isNotFound());
        mvc.perform(get("/api/links/" + due + "/stats"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(sweep.sweepOnce()).isGreaterThanOrEqualTo(1);

        mvc.perform(get("/api/links/" + due + "/stats"))
                .andExpect(jsonPath("$.status").value("EXPIRED"));
        assertThat(mapper.findByCode(notDue).getStatus()).isEqualTo(ShortLinkEntity.STATUS_ACTIVE);
        mvc.perform(get("/" + notDue)).andExpect(status().isFound());
    }

    private void insertDirectly(String code, LocalDateTime validUntil) {
        ShortLinkEntity entity = new ShortLinkEntity();
        entity.setCode(code);
        entity.setOriginalUrl(TARGET);
        entity.setCodeType(ShortLinkEntity.CODE_TYPE_HASH);
        entity.setStatus(ShortLinkEntity.STATUS_ACTIVE);
        entity.setValidUntil(validUntil);
        entity.setClickCount(0L);
        entity.setCreatedAt(utcNow());
        entity.setUpdatedAt(utcNow());
        mapper.insert(entity);
    }

    private List<String> partitions() {
        return jdbc.queryForList(
                "SELECT PARTITION_NAME FROM INFORMATION_SCHEMA.PARTITIONS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_link_click_log'"
                        + " AND PARTITION_NAME IS NOT NULL",
                String.class);
    }
}
