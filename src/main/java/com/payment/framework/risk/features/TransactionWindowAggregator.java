package com.payment.framework.risk.features;

import com.payment.framework.messaging.PaymentEvent;
import com.payment.framework.risk.domain.TransactionWindowFeatures;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Tracks how many payments happened recently, how many failed, average amounts, etc.
 * Keeps a rolling window of the last 5 minutes, forgets older stuff.
 */
@Slf4j
@Component
public class TransactionWindowAggregator {

    private static final long WINDOW_MS = 5 * 60 * 1000L;
    private static final long VELOCITY_1_MIN_MS = 60 * 1000L;
    private static final String ENTITY_TYPE_MERCHANT = "MERCHANT";
    private static final String ENTITY_TYPE_CARD = "CARD";
    private static final String ENTITY_TYPE_EMAIL = "EMAIL";
    private static final String ENTITY_TYPE_IP = "IP";

    private final Map<String, List<EventEntry>> store = new ConcurrentHashMap<>();
    /** Card-level window (key = card key from paymentMethodId or bin+last4). Enables per-card velocity/failure risk. */
    private final Map<String, List<EventEntry>> cardStore = new ConcurrentHashMap<>();
    /** Email-level window. Same email across card, wallet, BNPL. */
    private final Map<String, List<EventEntry>> emailStore = new ConcurrentHashMap<>();
    /** IP-level window. Same IP across all payment types. */
    private final Map<String, List<EventEntry>> ipStore = new ConcurrentHashMap<>();

    public void record(PaymentEvent event) {
        String entityId = entityIdFrom(event);
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
        String cardKey = cardKeyFrom(event);
        if (cardKey != null) {
            cardStore.compute(cardKey, (k, list) -> {
                if (list == null) list = new ArrayList<>();
                list.add(entry);
                long cutoff = System.currentTimeMillis() - WINDOW_MS;
                list.removeIf(e -> e.timestampMs < cutoff);
                return list;
            });
        }
        String emailKey = emailKeyFrom(event);
        if (emailKey != null) {
            emailStore.compute(emailKey, (k, list) -> {
                if (list == null) list = new ArrayList<>();
                list.add(entry);
                long cutoff = System.currentTimeMillis() - WINDOW_MS;
                list.removeIf(e -> e.timestampMs < cutoff);
                return list;
            });
        }
        String ipKey = ipKeyFrom(event);
        if (ipKey != null) {
            ipStore.compute(ipKey, (k, list) -> {
                if (list == null) list = new ArrayList<>();
                list.add(entry);
                long cutoff = System.currentTimeMillis() - WINDOW_MS;
                list.removeIf(e -> e.timestampMs < cutoff);
                return list;
            });
        }
    }

    private static String emailKeyFrom(PaymentEvent event) {
        if (event == null || event.getEmail() == null || event.getEmail().isBlank()) return null;
        return "email_" + event.getEmail().trim().toLowerCase();
    }

    private static String ipKeyFrom(PaymentEvent event) {
        if (event == null || event.getClientIp() == null || event.getClientIp().isBlank()) return null;
        return "ip_" + event.getClientIp().trim();
    }

    /**
     * Card/account identity key for aggregation. Prefer PAR (stable across tokens/cards), then fingerprint (hash of BIN+last4), then network token, BIN+last4, paymentMethodId.
     */
    public static String cardKeyFrom(PaymentEvent event) {
        if (event == null) return null;
        if (event.getPar() != null && !event.getPar().isBlank()) {
            return "par_" + event.getPar();
        }
        if (event.getCardFingerprint() != null && !event.getCardFingerprint().isBlank()) {
            return event.getCardFingerprint();
        }
        if (event.getNetworkToken() != null && !event.getNetworkToken().isBlank()) {
            return "nt_" + event.getNetworkToken();
        }
        if (event.getCardBin() != null && !event.getCardBin().isBlank()
                && event.getCardLast4() != null && !event.getCardLast4().isBlank()) {
            return "bin_" + event.getCardBin() + "_" + event.getCardLast4();
        }
        if (event.getPaymentMethodId() != null && !event.getPaymentMethodId().isBlank()) {
            return "pm_" + event.getPaymentMethodId();
        }
        return null;
    }

    public Optional<TransactionWindowFeatures> getFeaturesFromEvent(PaymentEvent event) {
        return getFeatures(entityIdFrom(event));
    }

    /** Per-card features (velocity, failure rate, etc.) when card identity is present. Use for card-level risk. */
    public Optional<TransactionWindowFeatures> getCardFeatures(String cardKey) {
        return getFeaturesForStore(cardKey, ENTITY_TYPE_CARD, cardStore);
    }

    public Optional<TransactionWindowFeatures> getCardFeaturesFromEvent(PaymentEvent event) {
        String cardKey = cardKeyFrom(event);
        return cardKey != null ? getCardFeatures(cardKey) : Optional.empty();
    }

    /** Per-email features (velocity, failure rate) across all payment types (card, wallet, BNPL). */
    public Optional<TransactionWindowFeatures> getEmailFeatures(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        return getFeaturesForStore("email_" + email.trim().toLowerCase(), ENTITY_TYPE_EMAIL, emailStore);
    }

    public Optional<TransactionWindowFeatures> getEmailFeaturesFromEvent(PaymentEvent event) {
        if (event == null || event.getEmail() == null || event.getEmail().isBlank()) return Optional.empty();
        return getEmailFeatures(event.getEmail());
    }

    /** Per-IP features (velocity, failure rate) across all payment types. */
    public Optional<TransactionWindowFeatures> getIpFeatures(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return Optional.empty();
        return getFeaturesForStore("ip_" + clientIp.trim(), ENTITY_TYPE_IP, ipStore);
    }

    public Optional<TransactionWindowFeatures> getIpFeaturesFromEvent(PaymentEvent event) {
        if (event == null || event.getClientIp() == null || event.getClientIp().isBlank()) return Optional.empty();
        return getIpFeatures(event.getClientIp());
    }

    private Optional<TransactionWindowFeatures> getFeaturesForStore(String entityId, String entityType, Map<String, List<EventEntry>> sourceStore) {
        List<EventEntry> entries = sourceStore.get(entityId);
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
        BigDecimal minAmount = inWindow.stream().map(e -> e.amount).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        long t1 = now - VELOCITY_1_MIN_MS;
        int count1Min = (int) inWindow.stream().filter(e -> e.timestampMs >= t1).count();

        EventEntry latest = inWindow.get(inWindow.size() - 1);
        ZonedDateTime latestTime = Instant.ofEpochMilli(latest.timestampMs).atZone(ZoneId.systemDefault());
        int hourOfDay = latestTime.getHour();
        int dayOfWeek = latestTime.getDayOfWeek().getValue() - 1;
        long secondsSinceLastTransaction = total > 1
                ? (latest.timestampMs - inWindow.get(inWindow.size() - 2).timestampMs) / 1000
                : 0;
        BigDecimal amountVariance = computeVariance(inWindow, avgAmount);
        double amountTrend = computeAmountTrend(inWindow);
        int increasingAmountCount = countIncreasingAmounts(inWindow);
        int decreasingAmountCount = countDecreasingAmounts(inWindow);
        double avgTimeGapSeconds = computeAvgTimeGap(inWindow);

        return Optional.of(TransactionWindowFeatures.builder()
                .entityId(entityId)
                .entityType(entityType)
                .windowStartEpochMs(windowStart)
                .windowEndEpochMs(now)
                .totalCount(total)
                .failureCount(failures)
                .failureRate(failureRate)
                .totalAmount(totalAmount)
                .avgAmount(avgAmount)
                .maxAmount(maxAmount)
                .minAmount(minAmount)
                .countLast1Min(count1Min)
                .countLast5Min(total)
                .hourOfDay(hourOfDay)
                .dayOfWeek(dayOfWeek)
                .secondsSinceLastTransaction(secondsSinceLastTransaction)
                .amountVariance(amountVariance)
                .amountTrend(amountTrend)
                .increasingAmountCount(increasingAmountCount)
                .decreasingAmountCount(decreasingAmountCount)
                .avgTimeGapSeconds(avgTimeGapSeconds)
                .build());
    }

    public Optional<TransactionWindowFeatures> getFeatures(String entityId) {
        return getFeaturesForStore(entityId, ENTITY_TYPE_MERCHANT, store);
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

    private BigDecimal computeVariance(List<EventEntry> entries, BigDecimal mean) {
        if (entries.size() < 2) return BigDecimal.ZERO;
        BigDecimal sumSquaredDiff = entries.stream()
                .map(e -> e.amount.subtract(mean))
                .map(diff -> diff.multiply(diff))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sumSquaredDiff.divide(BigDecimal.valueOf(entries.size()), 2, RoundingMode.HALF_UP);
    }

    private double computeAmountTrend(List<EventEntry> entries) {
        if (entries.size() < 2) return 0.0;
        // Simple linear regression: slope of amount over time
        // Positive = increasing amounts, negative = decreasing amounts
        long timeSpan = entries.get(entries.size() - 1).timestampMs - entries.get(0).timestampMs;
        if (timeSpan == 0) return 0.0;
        BigDecimal amountDiff = entries.get(entries.size() - 1).amount.subtract(entries.get(0).amount);
        return amountDiff.doubleValue() / (timeSpan / 1000.0); // Amount change per second
    }

    private int countIncreasingAmounts(List<EventEntry> entries) {
        if (entries.size() < 2) return 0;
        int count = 0;
        for (int i = 1; i < entries.size(); i++) {
            if (entries.get(i).amount.compareTo(entries.get(i - 1).amount) > 0) {
                count++;
            }
        }
        return count;
    }

    private int countDecreasingAmounts(List<EventEntry> entries) {
        if (entries.size() < 2) return 0;
        int count = 0;
        for (int i = 1; i < entries.size(); i++) {
            if (entries.get(i).amount.compareTo(entries.get(i - 1).amount) < 0) {
                count++;
            }
        }
        return count;
    }

    private double computeAvgTimeGap(List<EventEntry> entries) {
        if (entries.size() < 2) return 0.0;
        long totalGap = 0;
        for (int i = 1; i < entries.size(); i++) {
            totalGap += entries.get(i).timestampMs - entries.get(i - 1).timestampMs;
        }
        return (totalGap / 1000.0) / (entries.size() - 1); // Average gap in seconds
    }

    private record EventEntry(String eventId, long timestampMs, BigDecimal amount, boolean failure) {}
}
