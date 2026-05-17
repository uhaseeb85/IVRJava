package com.yourco.ivr.domain;

public enum AuthLevel {
    NONE(0), BASIC(1), STANDARD(2), ELEVATED(3), ADMIN(4);

    private final int rank;

    AuthLevel(int rank) {
        this.rank = rank;
    }

    public int getRank() {
        return rank;
    }

    public boolean isHigherThan(AuthLevel other) {
        return this.rank > other.rank;
    }
}
