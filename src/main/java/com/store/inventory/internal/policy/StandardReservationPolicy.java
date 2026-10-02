package com.store.inventory.internal.policy;

import java.time.Duration;

public final class StandardReservationPolicy implements ReservationPolicy {
    @Override
    public Duration ttl() {
        return Duration.ofMinutes(15);
    }

    @Override
    public int maxUnitsPerOrder() {
        return Integer.MAX_VALUE;
    }
}
