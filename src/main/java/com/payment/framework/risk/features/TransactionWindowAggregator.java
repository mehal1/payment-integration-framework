package com.payment.framework.risk.features;

import com.payment.framework.messaging.PaymentEvent;
import com.payment.framework.risk.domain.TransactionWindowFeatures;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Maintains rolling-window aggregates per entity (e.g. merchant) for risk scoring.
 * Uses in-memory storage; for production scale, replace with Redis or a proper
 * stream processor (e.g. Kafka Streams, Flink). Events older than windowMs are evicted.
 */
@Slf4j
@Component
public class TransactionWindowAggregator {

    private static final long WINDOW_MS = 5 * 60 * 1000L;  // 5 minutes
    private static final long VELOCITY_1_MIN_MS = 60 * 1000L;
    private static final String ENTITY_TYPE_MERCHANT = "MERCHANT";

    private final Map<String, List<EventEntry>> store = new ConcurrentHashMap<>();

    /**
     * Record an event and evict older entries for that entity.
     */
    public void record(PaymentEvent event) {
        String entityId = entityIdFrom(event);
        // Handle null timestamp - use current time as fallback
        long timestampMs = event.getTimestamp() != null 
                ? event.getTimestamp().toEpochMilli() 
                : System.currentTimeMillis();
        EventEntry entry = new EventEntry(
                event.getEventId(),
                timestampMs,
                event.getAmount() != null ? event.getAmount() : BigDecimal.ZERO,
                isFailure(event)
        );
        store.compute(entityId, (k, list) -> {
            if (list == null) list = new ArrayList<>();
            list.add(entry);
            long cutoff = System.currentTimeMillis() - WINDOW_MS;
            list.removeIf(e -> e.timestampMs < cutoff);
            return list;
        });
    }

    /**
     * Compute features for the given entity over the last window.
     */
    public Optional<TransactionWindowFeatures> getFeatures(String entityId) {
        List<EventEntry> entries = store.get(entityId);
        if (entries == null || entries.isEmpty()) return Optional.empty();

        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_MS;
        List<EventEntry> inWindow = entries.stream()
                .filter(e -> e.timestampMs >= windowStart)
                .sorted(Comparator.comparingLong(e -> e.timestampMs))
                .collect(Collectors.toList());
        if (inWindow.isEmpty()) return Optional.empty();

        int total = inWindow.size();
        int failures = (int) inWindow.stream().filter(e -> e.failure).count();
        double failureRate = total > 0 ? (double) failures / total : 0.0;
        BigDecimal totalAmount = inWindow.stream().map(e -> e.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgAmount = total > 0 ? totalAmount.divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal maxAmount = inWindow.stream().map(e -> e.amount).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        long t1 = now - VELOCITY_1_MIN_MS;
        int count1Min = (int) inWindow.stream().filter(e -> e.timestampMs >= t1).count();

        TransactionWindowFeatures features = TransactionWindowFeatures.builder()
                .entityId(entityId)
                .entityType(ENTITY_TYPE_MERCHANT)
                .windowStartEpochMs(windowStart)
                .windowEndEpochMs(now)
                .totalCount(total)
                .failureCount(failures)
                .failureRate(failureRate)
                .totalAmount(totalAmount)
                .avgAmount(avgAmount)
                .maxAmount(maxAmount)
                .countLast1Min(count1Min)
                .countLast5Min(total)
                .build();
        return Optional.of(features);
    }

    public Optional<TransactionWindowFeatures> getFeaturesFromEvent(PaymentEvent event) {
        return getFeatures(entityIdFrom(event));
    }

    private static String entityIdFrom(PaymentEvent event) {
        return event.getMerchantReference() != null && !event.getMerchantReference().isBlank()
                ? event.getMerchantReference()
                : (event.getCorrelationId() != null ? event.getCorrelationId() : "default");
    }

    private static boolean isFailure(PaymentEvent event) {
        if (event.getEventType() == null) return false;
        return "PAYMENT_FAILED".equals(event.getEventType());
    }

    private record EventEntry(String eventId, long timestampMs, BigDecimal amount, boolean failure) {}
}
