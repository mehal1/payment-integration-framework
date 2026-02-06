package com.payment.framework.risk.store;

import com.payment.framework.risk.domain.RiskAlert;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-memory store of the last N risk alerts for the REST API. In production
 * use a DB or query Kafka.
 */
@Component
public class RecentAlertsStore {

    private static final int MAX_RECENT = 100;
    private final ConcurrentLinkedDeque<RiskAlert> recent = new ConcurrentLinkedDeque<>();

    public void add(RiskAlert alert) {
        recent.addFirst(alert);
        while (recent.size() > MAX_RECENT) recent.removeLast();
    }

    public List<RiskAlert> getRecent(int limit) {
        List<RiskAlert> out = new ArrayList<>();
        int n = 0;
        for (RiskAlert a : recent) {
            if (n >= limit) break;
            out.add(a);
            n++;
        }
        return out;
    }
}
