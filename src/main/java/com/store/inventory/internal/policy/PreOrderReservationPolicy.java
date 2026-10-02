package com.store.inventory.internal.policy;

import java.time.Duration;

public final class PreOrderReservationPolicy implements ReservationPolicy {
    @Override
    public Duration ttl() {
        return Duration.ofHours(24);
    }

    @Override
    public int maxUnitsPerOrder() {
        return Integer.MAX_VALUE;
    }
}
