package com.store.inventory.internal.policy;

import com.store.inventory.api.ProductCategory;

public final class CategoryPolicy {
    private CategoryPolicy() {
    }

    public static ReservationPolicy forCategory(ProductCategory category) {
        return ReservationPolicy.from(category);
    }
}
