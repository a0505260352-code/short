package com.example.shortlink.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.shortlink.ratelimit.RateLimitProperties.Window;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Lua window against a real Redis. The script has to run on the server, so mocking the template
 * would prove nothing about the one thing this class is for: that counting and expiring happen in a
 * single atomic step.
 */
@Testcontainers(disabledWithoutDocker = true)
class RateLimitServiceTest {

    private static final Window TEN = new Window(10, 60);

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.1")
            .withExposedPorts(6379);

    private static LettuceConnectionFactory factory;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    private RateLimitService newService() {
        return new RateLimitService(new StringRedisTemplate(factory));
    }

    @Test
    void aBudgetIsSpentOneHitAtATimeAndThenRefuses() {
        RateLimitService service = newService();
        String bucket = bucket("spend");

        for (int hit = 1; hit <= 3; hit++) {
            assertThat(service.hit(bucket, new Window(3, 60)).allowed())
                    .as("hit %d of 3", hit)
                    .isTrue();
        }

        RateLimitService.Decision fourth = service.hit(bucket, new Window(3, 60));
        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    void theWindowExpiresAndTheBudgetComesBack() throws Exception {
        RateLimitService service = newService();
        String bucket = bucket("reset");
        Window onePerSecond = new Window(1, 1);

        assertThat(service.hit(bucket, onePerSecond).allowed()).isTrue();
        assertThat(service.hit(bucket, onePerSecond).allowed()).isFalse();

        TimeUnit.MILLISECONDS.sleep(1_200);
        assertThat(service.hit(bucket, onePerSecond).allowed())
                .as("the counter must expire with its window, not count forever")
                .isTrue();
    }

    @Test
    void oneClientsExhaustionLeavesOtherClientsAlone() {
        RateLimitService service = newService();
        String noisy = bucket("noisy");
        Window one = new Window(1, 60);

        service.hit(noisy, one);

        assertThat(service.hit(noisy, one).allowed()).isFalse();
        assertThat(service.hit(bucket("bystander"), one).allowed()).isTrue();
    }

    @Test
    void aCounterThatLostItsExpiryIsRepairedRatherThanLeftRejectingForever() {
        RateLimitService service = newService();
        String bucket = bucket("orphan");
        StringRedisTemplate raw = new StringRedisTemplate(factory);
        // The state a crash between INCR and PEXPIRE would leave, and the reason both are in one script.
        raw.opsForValue().set(bucket, "9");
        assertThat(raw.getExpire(bucket, TimeUnit.SECONDS)).isNegative();

        assertThat(service.hit(bucket, new Window(3, 60)).allowed()).isFalse();
        assertThat(raw.getExpire(bucket, TimeUnit.SECONDS)).isPositive();
    }

    @Test
    void anUnreachableRedisIsReportedAsUnknownBudgetNotAsPermission() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory("127.0.0.1", 1);
        dead.afterPropertiesSet();
        RateLimitService service = new RateLimitService(new StringRedisTemplate(dead));
        try {
            assertThatThrownBy(() -> service.hit(bucket("unreachable"), TEN))
                    .isInstanceOf(RateLimitUnavailableException.class);
        } finally {
            dead.destroy();
        }
    }

    /** Distinct per run so a reused container cannot carry a counter over from an earlier test. */
    private static String bucket(String name) {
        return "rl-test:" + name + ":" + System.nanoTime();
    }
}
