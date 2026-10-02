package com.store.inventory.internal.service;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.internal.domain.ProductStock;
import com.store.inventory.internal.policy.ReservationPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class InventoryServiceImpl implements InventoryService {
    private final Clock clock;
    private final StockAlertListener alertListener;
    private final Map<String, ProductStock> products = new ConcurrentHashMap<>();
    private final Map<String, Reservation> reservationsByOrderId = new ConcurrentHashMap<>();

    public InventoryServiceImpl(Clock clock, StockAlertListener alertListener) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.alertListener = alertListener == null ? (sku, availableUnits) -> { } : alertListener;
    }

    @Override
    public void registerProduct(String sku, ProductCategory category) {
        validateSku(sku);
        Objects.requireNonNull(category, "category");
        products.computeIfAbsent(sku, key -> new ProductStock(sku, category, ReservationPolicy.from(category)));
    }

    @Override
    public void addStock(String sku, int quantity) {
        requirePositiveQuantity(quantity, "Stock quantity");
        ProductStock product = requireProduct(sku);
        product.withLock(() -> {
            removeExpiredReservations(product);
            product.addStock(quantity);
            maybeEmitLowStockAlert(product);
        });
    }

    @Override
    public Reservation reserve(String orderId, String sku, int quantity) {
        validateOrderId(orderId);
        requirePositiveQuantity(quantity, "Reservation quantity");
        ProductStock product = products.get(sku);
        if (product == null) {
            throw new InsufficientStockException(sku, quantity, 0);
        }

        return product.withLock(() -> {
            removeExpiredReservations(product);
            Reservation activeReservation = reservationsByOrderId.get(orderId);
            if (activeReservation != null && sku.equals(activeReservation.sku())) {
                if (isActive(activeReservation, clock.instant())) {
                    return activeReservation;
                }
                reservationsByOrderId.remove(orderId);
            }
            if (activeReservation != null && !sku.equals(activeReservation.sku())) {
                throw new IllegalStateException("Order id " + orderId + " is already reserved for a different product");
            }

            com.store.inventory.internal.domain.Reservation existing = product.getReservation(orderId);
            if (existing != null && isActive(existing.expiresAt(), clock.instant())) {
                return toApiReservation(existing);
            }
            if (existing != null) {
                product.releaseReservation(orderId);
            }

            int limit = product.getPolicy().maxUnitsPerOrder();
            if (limit != Integer.MAX_VALUE && quantity > limit) {
                throw new OrderLimitExceededException(sku, quantity, limit);
            }

            int available = product.availableUnits();
            if (available < quantity) {
                throw new InsufficientStockException(sku, quantity, available);
            }

            Instant expiresAt = clock.instant().plus(product.getPolicy().ttl());
            product.reserveUnits(orderId, quantity, expiresAt);
            Reservation apiReservation = new Reservation(orderId, sku, quantity, expiresAt);
            reservationsByOrderId.put(orderId, apiReservation);
            maybeEmitLowStockAlert(product);
            return apiReservation;
        });
    }

    @Override
    public void confirm(String orderId) {
        validateOrderId(orderId);
        Reservation reservation = reservationsByOrderId.get(orderId);
        if (reservation == null) {
            throw new IllegalStateException("Order has no active reservation");
        }

        ProductStock product = products.get(reservation.sku());
        if (product == null) {
            reservationsByOrderId.remove(orderId);
            throw new IllegalStateException("Order has no active reservation");
        }

        product.withLock(() -> {
            removeExpiredReservations(product);
            com.store.inventory.internal.domain.Reservation internal = product.getReservation(orderId);
            if (internal == null || !isActive(internal.expiresAt(), clock.instant())) {
                reservationsByOrderId.remove(orderId);
                throw new IllegalStateException("Order has no active reservation");
            }
            product.confirmReservation(orderId);
            reservationsByOrderId.remove(orderId);
            maybeEmitLowStockAlert(product);
        });
    }

    @Override
    public int available(String sku) {
        ProductStock product = products.get(sku);
        if (product == null) {
            return 0;
        }
        return product.withLock(() -> {
            removeExpiredReservations(product);
            int available = product.availableUnits();
            maybeEmitLowStockAlert(product);
            return available;
        });
    }

    private void removeExpiredReservations(ProductStock product) {
        Instant now = clock.instant();
        for (com.store.inventory.internal.domain.Reservation expired : product.expireReservations(now)) {
            reservationsByOrderId.remove(expired.orderId());
        }
        if (product.availableUnits() > 5) {
            product.setLowStockAlertEmitted(false);
        }
    }

    private void maybeEmitLowStockAlert(ProductStock product) {
        int available = product.availableUnits();
        if (available <= 5) {
            if (!product.isLowStockAlertEmitted()) {
                alertListener.onLowStock(product.getSku(), available);
                product.setLowStockAlertEmitted(true);
            }
        } else {
            product.setLowStockAlertEmitted(false);
        }
    }

    private static void validateSku(String sku) {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("SKU is required");
        }
    }

    private static void validateOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("Order id is required");
        }
    }

    private static void requirePositiveQuantity(int quantity, String label) {
        if (quantity <= 0) {
            throw new IllegalArgumentException(label + " must be positive");
        }
    }

    private ProductStock requireProduct(String sku) {
        ProductStock product = products.get(sku);
        if (product == null) {
            throw new IllegalArgumentException("Product " + sku + " is not registered");
        }
        return product;
    }

    private static boolean isActive(Instant expiration, Instant now) {
        return now.isBefore(expiration);
    }

    private static boolean isActive(Reservation reservation, Instant now) {
        return reservation != null && now.isBefore(reservation.expiresAt());
    }

    private static Reservation toApiReservation(com.store.inventory.internal.domain.Reservation reservation) {
        return new Reservation(reservation.orderId(), reservation.sku(), reservation.quantity(), reservation.expiresAt());
    }
}
