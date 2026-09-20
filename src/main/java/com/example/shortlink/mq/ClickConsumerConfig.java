package com.example.shortlink.mq;

import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

/**
 * Declares the functional consumers the RocketMQ binder binds to.
 *
 * <p>The bean name is the binding name: {@code clickConsumer} is bound by {@code clickConsumer-in-0} in
 * {@code application.yml}, so renaming it without renaming the binding silently stops consumption.
 *
 * <p>Returning without throwing is deliberate. The binder turns an escaped exception into a redelivery
 * only if the framework's own retry wrapper is configured not to swallow it first, so backpressure is
 * applied by parking this thread inside {@link ClickBatchWriter#accept} instead — a mechanism that does
 * not depend on those internals.
 */
@Configuration(proxyBeanMethods = false)
public class ClickConsumerConfig {

    @Bean
    Consumer<Message<ClickEvent>> clickConsumer(ClickBatchWriter writer) {
        return message -> writer.accept(message.getPayload());
    }
}
