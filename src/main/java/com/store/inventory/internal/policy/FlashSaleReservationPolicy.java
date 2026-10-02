package com.store.inventory.internal.policy;

import java.time.Duration;

public final class FlashSaleReservationPolicy implements ReservationPolicy {
    @Override
    public Duration ttl() {
        return Duration.ofMinutes(5);
    }

    @Override
    public int maxUnitsPerOrder() {
        return 2;
    }
}
