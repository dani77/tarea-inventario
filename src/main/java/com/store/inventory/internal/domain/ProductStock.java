package com.store.inventory.internal.domain;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.internal.policy.ReservationPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public final class ProductStock {
    private final String sku;
    private final ProductCategory category;
    private final ReservationPolicy policy;
    private final AtomicInteger totalUnits = new AtomicInteger();
    private final AtomicInteger reservedUnits = new AtomicInteger();
    private final AtomicBoolean lowStockAlertEmitted = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Reservation> reservations = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public ProductStock(String sku, ProductCategory category, ReservationPolicy policy) {
        this.sku = Objects.requireNonNull(sku, "sku");
        this.category = Objects.requireNonNull(category, "category");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public String getSku() {
        return sku;
    }

    public ProductCategory getCategory() {
        return category;
    }

    public ReservationPolicy getPolicy() {
        return policy;
    }

    public int getTotalUnits() {
        return totalUnits.get();
    }

    public int getReservedUnits() {
        return reservedUnits.get();
    }

    public int availableUnits() {
        return Math.max(0, totalUnits.get() - reservedUnits.get());
    }

    public boolean isLowStockAlertEmitted() {
        return lowStockAlertEmitted.get();
    }

    public void setLowStockAlertEmitted(boolean emitted) {
        lowStockAlertEmitted.set(emitted);
    }

    public void addStock(int quantity) {
        totalUnits.addAndGet(quantity);
    }

    public void reserveUnits(String orderId, int quantity, Instant expiresAt) {
        reservations.put(orderId, new Reservation(orderId, sku, quantity, expiresAt, ReservationStatus.ACTIVE));
        reservedUnits.addAndGet(quantity);
    }

    public void releaseReservation(String orderId) {
        Reservation reservation = reservations.remove(orderId);
        if (reservation != null) {
            reservedUnits.addAndGet(-reservation.quantity());
        }
    }

    public void confirmReservation(String orderId) {
        Reservation reservation = reservations.remove(orderId);
        if (reservation == null) {
            return;
        }
        reservedUnits.addAndGet(-reservation.quantity());
        totalUnits.addAndGet(-reservation.quantity());
    }

    public Reservation getReservation(String orderId) {
        return reservations.get(orderId);
    }

    public List<Reservation> expireReservations(Instant now) {
        List<Reservation> expired = new ArrayList<>();
        reservations.forEach((orderId, reservation) -> {
            if (!now.isBefore(reservation.expiresAt())) {
                expired.add(reservation);
            }
        });
        for (Reservation reservation : expired) {
            reservations.remove(reservation.orderId(), reservation);
            reservedUnits.addAndGet(-reservation.quantity());
        }
        return expired;
    }

    public void withLock(Runnable action) {
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    public <T> T withLock(java.util.function.Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
