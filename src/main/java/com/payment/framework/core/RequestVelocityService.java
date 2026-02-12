package com.payment.framework.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * At ingestion: how many requests has this Email/IP sent in the last 60 seconds?
 * Used to block or flag high-velocity abuse before routing.
 */
@Slf4j
@Service
public class RequestVelocityService {

    private static final long WINDOW_MS = 60_000L;

    private final Map<String, CopyOnWriteArrayList<Long>> byEmail = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Long>> byIp = new ConcurrentHashMap<>();

    @Value("${payment.velocity.max-per-email-per-60s:0}")
    private int maxPerEmailPer60s;

    @Value("${payment.velocity.max-per-ip-per-60s:0}")
    private int maxPerIpPer60s;

    /**
     * Record this request and return current counts in the last 60 seconds for this email and IP.
     * Call at ingestion (before execute).
     */
    public VelocitySnapshot recordAndCheck(String email, String clientIp) {
        long now = System.currentTimeMillis();
        long cutoff = now - WINDOW_MS;

        int emailCount = 0;
        if (email != null && !email.isBlank()) {
            byEmail.computeIfAbsent(email, k -> new CopyOnWriteArrayList<>()).add(now);
            CopyOnWriteArrayList<Long> list = byEmail.get(email);
            list.removeIf(ts -> ts < cutoff);
            emailCount = list.size();
        }

        int ipCount = 0;
        if (clientIp != null && !clientIp.isBlank()) {
            byIp.computeIfAbsent(clientIp, k -> new CopyOnWriteArrayList<>()).add(now);
            CopyOnWriteArrayList<Long> list = byIp.get(clientIp);
            list.removeIf(ts -> ts < cutoff);
            ipCount = list.size();
        }

        boolean overThreshold = (maxPerEmailPer60s > 0 && emailCount > maxPerEmailPer60s)
                || (maxPerIpPer60s > 0 && ipCount > maxPerIpPer60s);
        if (overThreshold) {
            log.warn("Velocity check: email={} emailCount={} ip={} ipCount={} (thresholds: email={}, ip={})",
                    email != null ? "***" : null, emailCount, clientIp != null ? "***" : null, ipCount,
                    maxPerEmailPer60s, maxPerIpPer60s);
        }

        return new VelocitySnapshot(emailCount, ipCount, overThreshold);
    }

    public int getCountByEmailLast60s(String email) {
        if (email == null || email.isBlank()) return 0;
        CopyOnWriteArrayList<Long> list = byEmail.get(email);
        if (list == null) return 0;
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        return (int) list.stream().filter(ts -> ts >= cutoff).count();
    }

    public int getCountByIpLast60s(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return 0;
        CopyOnWriteArrayList<Long> list = byIp.get(clientIp);
        if (list == null) return 0;
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        return (int) list.stream().filter(ts -> ts >= cutoff).count();
    }

    @lombok.Value
    public static class VelocitySnapshot {
        int emailCountLast60s;
        int ipCountLast60s;
        boolean overThreshold;
    }
}
