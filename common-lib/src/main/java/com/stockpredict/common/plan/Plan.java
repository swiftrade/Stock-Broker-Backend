package com.stockpredict.common.plan;

/** Subscription plan carried in the JWT {@code plan} claim. */
public enum Plan {

    FREE(0),
    PREMIUM(1);

    private final int rank;

    Plan(int rank) {
        this.rank = rank;
    }

    public boolean allows(Plan required) {
        return this.rank >= required.rank;
    }

    public static Plan from(String value) {
        if (value == null || value.isBlank()) {
            return FREE;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return FREE;
        }
    }
}
