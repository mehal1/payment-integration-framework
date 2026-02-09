package com.payment.framework.risk.messaging;

import com.payment.framework.risk.domain.RiskAlert;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Delivers risk alerts to merchant webhook endpoints in real-time.
 * Handles retry logic with exponential backoff and async delivery to avoid blocking alert processing.
 */
@Slf4j
@Service
public class WebhookService {

    private final RestTemplate restTemplate;
    private final ScheduledExecutorService executorService;
    private final Map<String, List<String>> merchantWebhooks;
    
    @Value("${payment.risk.webhook.enabled:false}")
    private boolean webhookEnabled;
    
    @Value("${payment.risk.webhook.max-retries:3}")
    private int maxRetries;
    
    @Value("${payment.risk.webhook.retry-delay-ms:1000}")
    private long retryDelayMs;
    
    @Value("${payment.risk.webhook.timeout-ms:5000}")
    private int timeoutMs;

    public WebhookService(@Value("${payment.risk.webhook.timeout-ms:5000}") int timeoutMs) {
        this.timeoutMs = timeoutMs;
        this.merchantWebhooks = new ConcurrentHashMap<>();
        this.restTemplate = new RestTemplate();
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        restTemplate.setRequestFactory(factory);
        this.executorService = Executors.newScheduledThreadPool(10);
    }

    /**
     * Sends alert to all registered webhook URLs for the entity asynchronously.
     * Automatically retries failed deliveries up to maxRetries times with exponential backoff.
     */
    public void sendAlert(RiskAlert alert) {
        if (!webhookEnabled) {
            return;
        }

        String entityId = alert.getEntityId();
        List<String> webhookUrls = merchantWebhooks.get(entityId);
        
        if (webhookUrls == null || webhookUrls.isEmpty()) {
            return;
        }

        for (String webhookUrl : webhookUrls) {
            CompletableFuture.runAsync(() -> sendWithRetry(webhookUrl, alert, 0), executorService)
                    .exceptionally(ex -> {
                        log.error("Failed to schedule webhook delivery to {} for alert {}", 
                                webhookUrl, alert.getAlertId(), ex);
                        return null;
                    });
        }
    }

    private void sendWithRetry(String webhookUrl, RiskAlert alert, int attempt) {
        try {
            restTemplate.postForEntity(webhookUrl, alert, String.class);
            log.info("Successfully delivered alert {} to webhook {}", 
                    alert.getAlertId(), webhookUrl);
        } catch (Exception e) {
            if (attempt < maxRetries) {
                long delay = retryDelayMs * (attempt + 1);
                log.warn("Webhook delivery failed for alert {} to {} (attempt {}), retrying in {}ms: {}", 
                        alert.getAlertId(), webhookUrl, attempt + 1, delay, e.getMessage());
                
                executorService.schedule(() -> sendWithRetry(webhookUrl, alert, attempt + 1), 
                        delay, TimeUnit.MILLISECONDS);
            } else {
                log.error("Failed to deliver alert {} to webhook {} after {} attempts", 
                        alert.getAlertId(), webhookUrl, maxRetries + 1, e);
            }
        }
    }

    /**
     * Registers a webhook URL to receive alerts for a specific entity (merchant or customer).
     * Note: In production, webhook URLs should be persisted to a database.
     */
    public void registerWebhook(String entityId, String webhookUrl) {
        log.info("Registering webhook {} for entity {}", webhookUrl, entityId);
        merchantWebhooks.computeIfAbsent(entityId, k -> new ArrayList<>()).add(webhookUrl);
    }

    /**
     * Removes a webhook URL registration for an entity.
     */
    public void unregisterWebhook(String entityId, String webhookUrl) {
        List<String> urls = merchantWebhooks.get(entityId);
        if (urls != null) {
            urls.remove(webhookUrl);
            if (urls.isEmpty()) {
                merchantWebhooks.remove(entityId);
            }
            log.info("Unregistered webhook {} for entity {}", webhookUrl, entityId);
        }
    }

    /**
     * Returns all registered webhook URLs for an entity.
     */
    public List<String> getWebhooks(String entityId) {
        return merchantWebhooks.getOrDefault(entityId, List.of());
    }
}
