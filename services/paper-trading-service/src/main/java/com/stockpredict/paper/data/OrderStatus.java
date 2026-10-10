package com.stockpredict.paper.data;

import java.util.Locale;

/**
 * Order lifecycle statuses (E4). "Working" orders can still fill or be
 * cancelled; the other states are terminal.
 */
public enum OrderStatus {
    NEW,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED,
    EXPIRED;

    public boolean isWorking() {
        return this == NEW || this == PARTIALLY_FILLED;
    }

    /** Lenient parse; returns {@code null} for unknown/blank values. */
    public static OrderStatus from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
