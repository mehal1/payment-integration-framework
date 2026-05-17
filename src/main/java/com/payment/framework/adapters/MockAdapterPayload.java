package com.payment.framework.adapters;

import java.util.Map;

final class MockAdapterPayload {

    private MockAdapterPayload() {
    }

    static String get(Map<String, Object> payload, String key) {
        if (payload == null || key == null) {
            return null;
        }
        Object v = payload.get(key);
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
