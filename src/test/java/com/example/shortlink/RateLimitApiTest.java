package com.example.shortlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Limits as the client sees them: the interceptor registration, the 429 body and the Retry-After header.
 *
 * <p>The tiny limits here are the point of having a second context. Production values would make the
 * test send hundreds of requests to reach a rejection, and a test that has to beg for a limit is a test
 * that will be deleted the first time it flakes.
 */
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.cloud.stream.bindings.clickConsumer-in-0.consumer.auto-startup=false",
        "spring.cloud.stream.rocketmq.binder.name-server=127.0.0.1:1",
        "shortlink.ratelimit.create-per-ip-limit=2",
        "shortlink.ratelimit.create-per-ip-window-seconds=60",
        "shortlink.ratelimit.create-global-limit=5",
        "shortlink.ratelimit.create-global-window-seconds=60",
        "shortlink.ratelimit.redirect-per-ip-limit=3",
        "shortlink.ratelimit.redirect-per-ip-window-seconds=60"})
class RateLimitApiTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("shortlink")
            .withUsername("shortlink")
            .withPassword("shortlink");

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1").withExposedPorts(6379);

    private static final String CREATE_PATH = "/api/links";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ObjectMapper json;

    private int urlSequence;

    /**
     * Counters outlive a test inside one context, and the global bucket is deliberately shared between
     * clients — so without a reset the tests would leak into each other and only pass in one order.
     */
    @BeforeEach
    void emptyBuckets() {
        deleteMatching("create-ip:*");
        deleteMatching("create-global:*");
        deleteMatching("redirect-ip:*");
    }

    @Test
    void theThirdCreateFromOneClientIsRefusedAndToldWhenToComeBack() throws Exception {
        String client = "10.20.0.1";
        mvc.perform(create(client)).andExpect(status().isCreated());
        mvc.perform(create(client)).andExpect(status().isCreated());

        var refused = mvc.perform(create(client)).andExpect(status().isTooManyRequests())
                .andReturn().getResponse();
        assertThat(json.readTree(refused.getContentAsString()).path("status").asInt()).isEqualTo(429);
        assertThat(Integer.parseInt(refused.getHeader("Retry-After")))
                .as("the window is 60s, so the wait must be inside it")
                .isBetween(1, 60);

        // A refusal is per client: an unrelated address is not punished for this one's volume.
        mvc.perform(create("10.20.0.2")).andExpect(status().isCreated());
    }

    @Test
    void theGlobalCreateBudgetIsSharedByEveryClient() throws Exception {
        for (int client = 1; client <= 5; client++) {
            mvc.perform(create("10.21.0." + client)).andExpect(status().isCreated());
        }

        // Its own bucket holds one request, so only a counter every client shares can refuse this one.
        mvc.perform(create("10.21.0.99")).andExpect(status().isTooManyRequests());
    }

    @Test
    void aVisitorWhoOverloadsTheRedirectPathIsRefusedWithoutLockingOutOthers() throws Exception {
        String code = createdCode();

        String client = "10.22.0.1";
        for (int hit = 1; hit <= 3; hit++) {
            mvc.perform(redirect(client, code)).andExpect(status().isFound());
        }
        mvc.perform(redirect(client, code)).andExpect(status().isTooManyRequests());

        mvc.perform(redirect("10.22.0.2", code)).andExpect(status().isFound());
    }

    private String createdCode() throws Exception {
        String body = mvc.perform(create("10.23.0.1")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("code").asText();
    }

    private MockHttpServletRequestBuilder create(String remoteAddr) {
        String body = "{\"url\":\"https://example.com/rate-limit/" + (++urlSequence) + "\"}";
        return from(remoteAddr, post(CREATE_PATH))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder redirect(String remoteAddr, String code) {
        return from(remoteAddr, get("/" + code));
    }

    private static MockHttpServletRequestBuilder from(String remoteAddr, MockHttpServletRequestBuilder builder) {
        return builder.with(request -> {
            request.setRemoteAddr(remoteAddr);
            return request;
        });
    }

    private void deleteMatching(String pattern) {
        Set<String> keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
