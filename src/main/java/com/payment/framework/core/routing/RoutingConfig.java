package com.payment.framework.core.routing;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Configuration for provider routing strategies.
 * Selects routing strategy based on configuration property.
 */
@Configuration
@RequiredArgsConstructor
public class RoutingConfig {

    private final List<ProviderRoutingStrategy> strategies;

    @Value("${payment.routing.strategy:WeightedRoundRobin}")
    private String routingStrategyName;

    @Bean
    public ProviderRoutingStrategy routingStrategy() {
        Map<String, ProviderRoutingStrategy> strategyMap = strategies.stream()
                .collect(Collectors.toMap(
                        ProviderRoutingStrategy::getStrategyName,
                        Function.identity()
                ));

        ProviderRoutingStrategy selected = strategyMap.get(routingStrategyName);
        if (selected == null) {
            throw new IllegalArgumentException(
                    "Unknown routing strategy: " + routingStrategyName +
                    ". Available: " + strategyMap.keySet());
        }

        return selected;
    }
}
