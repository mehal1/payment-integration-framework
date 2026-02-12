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
 * Asks our ML service "is this payment sketchy?" Returns empty if ML service is down.
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
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        restTemplate.setRequestFactory(factory);
    }

    /**
     * Ask ML service how risky this payment is (0.0 = safe, 1.0 = very suspicious).
     * @return empty if ML service is down or returns garbage
     */
    public Optional<Double> score(TransactionWindowFeatures features) {
        try {
            // Build request with all features (ML service can use what it needs)
            Map<String, Object> request = new java.util.HashMap<>();
            // Original features
            request.put("totalCount", features.getTotalCount());
            request.put("failureCount", features.getFailureCount());
            request.put("failureRate", features.getFailureRate());
            request.put("countLast1Min", features.getCountLast1Min());
            request.put("avgAmount", features.getAvgAmount().doubleValue());
            request.put("maxAmount", features.getMaxAmount().doubleValue());
            // Enhanced features
            request.put("minAmount", features.getMinAmount().doubleValue());
            request.put("hourOfDay", features.getHourOfDay());
            request.put("dayOfWeek", features.getDayOfWeek());
            request.put("secondsSinceLastTransaction", features.getSecondsSinceLastTransaction());
            request.put("amountVariance", features.getAmountVariance().doubleValue());
            request.put("amountTrend", features.getAmountTrend());
            request.put("increasingAmountCount", features.getIncreasingAmountCount());
            request.put("decreasingAmountCount", features.getDecreasingAmountCount());
            request.put("avgTimeGapSeconds", features.getAvgTimeGapSeconds());

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
