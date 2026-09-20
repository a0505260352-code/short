package com.example.shortlink.bloom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.shortlink.codec.CodeHasher;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the {@code BF.*} scripts against a real Redis, because the whole point of Redis Open
 * Source 8 is that the bloom module is compiled in; a mock would happily pass a script the server
 * cannot run.
 */
@Testcontainers(disabledWithoutDocker = true)
class BloomServiceTest {

    private static final String KEY = "bf:codes-test";

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

    private BloomService newService() {
        return new BloomService(new StringRedisTemplate(factory),
                new BloomProperties(KEY, 0.01, 10_000, 2));
    }

    @Test
    void reserveTwiceIsNotAnError() {
        BloomService service = newService();
        assertThatCode(service::reserve).doesNotThrowAnyException();
        assertThatCode(service::reserve).doesNotThrowAnyException();
    }

    @Test
    void addedCodeBecomesProbablyPresent() {
        BloomService service = newService();
        service.reserve();
        assertThat(service.possiblyExists("Ab3dE7")).isFalse();
        service.add("Ab3dE7");
        assertThat(service.possiblyExists("Ab3dE7")).isTrue();
    }

    @Test
    void neverReportsAbsentItemsAsPresentInBulk() {
        BloomService service = newService();
        service.reserve();
        List<String> issued = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            issued.add(CodeHasher.code("https://example.com/no-false-negative/" + i));
        }
        issued.forEach(service::add);
        assertThat(issued).allMatch(service::possiblyExists);
    }

    @Test
    void worksOnAFilterReserveNeverCreated() {
        // BF.ADD auto-creates with the module's own defaults, which is why a failed BF.RESERVE only
        // costs capacity planning instead of breaking link creation.
        BloomService service = new BloomService(new StringRedisTemplate(factory),
                new BloomProperties(KEY + "-unreserved", 0.01, 10_000, 2));
        assertThat(service.possiblyExists("zzzzzz")).isFalse();
        service.add("zzzzzz");
        assertThat(service.possiblyExists("zzzzzz")).isTrue();
    }
}
