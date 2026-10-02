package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InventoryBusinessRulesTest {

    @Test
    void duplicateOrderIdIsIdempotent() {
        MutableClock clock = new MutableClock(Instant.parse("2025-01-01T00:00:00Z"));
        InventoryService service = Inventory.create(clock, (sku, available) -> { });
        service.registerProduct("SKU-SEQ", ProductCategory.STANDARD);
        service.addStock("SKU-SEQ", 10);

        Reservation first = service.reserve("ORDER-42", "SKU-SEQ", 4);
        Reservation second = service.reserve("ORDER-42", "SKU-SEQ", 4);

        assertEquals(first, second);
        assertEquals(6, service.available("SKU-SEQ"));
    }

    @Test
    void flashSaleHonorsTwoUnitsPerOrder() {
        InventoryService service = Inventory.create(Clock.systemUTC(), (sku, available) -> { });
        service.registerProduct("SKU-FLASH", ProductCategory.FLASH_SALE);
        service.addStock("SKU-FLASH", 5);

        assertThrows(OrderLimitExceededException.class, () -> service.reserve("ORDER-1", "SKU-FLASH", 3));
        service.reserve("ORDER-2", "SKU-FLASH", 2);
        assertEquals(3, service.available("SKU-FLASH"));
    }

    @Test
    void reservationsExpireAndReleaseStock() {
        MutableClock clock = new MutableClock(Instant.parse("2025-01-01T00:00:00Z"));
        InventoryService service = Inventory.create(clock, (sku, available) -> { });
        service.registerProduct("SKU-TTL", ProductCategory.STANDARD);
        service.addStock("SKU-TTL", 10);

        service.reserve("ORDER-TTL", "SKU-TTL", 4);
        assertEquals(6, service.available("SKU-TTL"));

        clock.advance(Duration.ofMinutes(16));

        assertEquals(10, service.available("SKU-TTL"));
    }

    @Test
    void stockAlertEmitsOncePerLowStockCycle() {
        MutableClock clock = new MutableClock(Instant.parse("2025-01-01T00:00:00Z"));
        AtomicInteger alerts = new AtomicInteger();
        InventoryService service = Inventory.create(clock, (sku, available) -> alerts.incrementAndGet());
        service.registerProduct("SKU-ALERT", ProductCategory.STANDARD);
        service.addStock("SKU-ALERT", 6);

        service.reserve("ORDER-1", "SKU-ALERT", 1);
        assertEquals(1, alerts.get());

        service.reserve("ORDER-2", "SKU-ALERT", 1);
        assertEquals(1, alerts.get());

        service.confirm("ORDER-1");
        service.confirm("ORDER-2");
        service.addStock("SKU-ALERT", 10);
        assertEquals(1, alerts.get());

        service.reserve("ORDER-3", "SKU-ALERT", 9);
        assertEquals(2, alerts.get());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant initialInstant) {
            this.instant = initialInstant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        public void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }
    }
}
