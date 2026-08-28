package com.perf.regionalorchestrator.relay;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds {@link RelayProperties} from {@code regionalOrchestrator.relay.*}. */
@Configuration
public class RelayConfig {

    @Bean
    public RelayProperties relayProperties(
            @Value("${regionalOrchestrator.relay.connectTimeoutMs:2000}") long connectTimeoutMs,
            @Value("${regionalOrchestrator.relay.readTimeoutMs:15000}")   long readTimeoutMs) {
        return new RelayProperties(connectTimeoutMs, readTimeoutMs);
    }
}
