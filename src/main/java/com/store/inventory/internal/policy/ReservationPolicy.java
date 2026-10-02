package com.store.inventory.internal.policy;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;

public interface ReservationPolicy {
    Duration ttl();

    int maxUnitsPerOrder();

    static ReservationPolicy from(ProductCategory category) {
        return switch (category) {
            case STANDARD -> new StandardReservationPolicy();
            case PRE_ORDER -> new PreOrderReservationPolicy();
            case FLASH_SALE -> new FlashSaleReservationPolicy();
        };
    }
}
