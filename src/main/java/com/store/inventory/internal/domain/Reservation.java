package com.store.inventory.internal.domain;

import java.time.Instant;

public record Reservation(String orderId, String sku, int quantity, Instant expiresAt, ReservationStatus status) {
    public Reservation {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("Order id is required");
        }
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("SKU is required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Reservation quantity must be positive");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("Expiration instant is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("Status is required");
        }
    }
}
