package com.payment.framework.risk.engine;

import com.payment.framework.risk.domain.TransactionWindowFeatures;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;

/**
 * Calls the external ML service (risk-ml-service) to get risk scores.
 * Falls back gracefully if the service is unavailable (returns empty).
 * Enable with: payment.risk.ml.enabled=true
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "payment.risk.ml.enabled", havingValue = "true", matchIfMissing = false)
public class MlRiskScorer {

    @Value("${payment.risk.ml.service.url:http://localhost:5000/predict}")
    private String mlServiceUrl;

    @Value("${payment.risk.ml.service.timeout:2000}")
    private int timeoutMs;

    private final RestTemplate restTemplate;

    public MlRiskScorer() {
        this.restTemplate = new RestTemplate();
        // Set timeout
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        restTemplate.setRequestFactory(factory);
    }

    /**
     * Get ML risk score from the external service.
     * @param features Transaction window features
     * @return Optional risk score (0.0-1.0), empty if service unavailable
     */
    public Optional<Double> score(TransactionWindowFeatures features) {
        try {
            Map<String, Object> request = Map.of(
                    "totalCount", features.getTotalCount(),
                    "failureCount", features.getFailureCount(),
                    "failureRate", features.getFailureRate(),
                    "countLast1Min", features.getCountLast1Min(),
                    "avgAmount", features.getAvgAmount().doubleValue(),
                    "maxAmount", features.getMaxAmount().doubleValue()
            );

            Map<String, Object> response = restTemplate.postForObject(
                    mlServiceUrl, request, Map.class
            );

            if (response != null && response.containsKey("riskScore")) {
                Object scoreObj = response.get("riskScore");
                double score = scoreObj instanceof Number
                        ? ((Number) scoreObj).doubleValue()
                        : Double.parseDouble(scoreObj.toString());
                log.debug("ML service returned score: {} for entity {}", score, features.getEntityId());
                return Optional.of(score);
            }
            log.warn("ML service response missing riskScore field");
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("ML service call failed for entity {}: {}", features.getEntityId(), e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.error("Unexpected error calling ML service", e);
            return Optional.empty();
        }
    }
}
