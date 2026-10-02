# Reservas de inventario

Este documento explica los elementos creados para cumplir los requerimientos de reservas temporales, políticas por categoría, control de stock y alertas de compras.

## Objetivo de la implementación

Se desarrolló un servicio de inventario en memoria que:

- reserva unidades al recibir un pedido,
- libera reservas vencidas cuando expira el tiempo de pago,
- confirma ventas cuando el pago se aprueba,
- respeta reglas por categoría de producto,
- evita sobreventa en entornos concurrentes,
- evita alertas repetidas mientras el stock siga bajo,
- mantiene la lógica extensible para futuras categorías.

La solución quedó organizada bajo el paquete interno `com.store.inventory.internal` y mantiene intacto el contrato público definido en `com.store.inventory.api`.

## Estructura creada

### 1. Punto de entrada

#### `com.store.inventory.Inventory`

Es la fábrica del servicio, con la firma solicitada:

- `Inventory.create(Clock clock, StockAlertListener alertListener)`

Su rol es crear la implementación concreta del servicio y entregar el `Clock` y el `StockAlertListener` que deben usar todas las operaciones.

Esto permite:

- pruebas deterministas con un `Clock` controlado,
- inyección de lógica de alerta para compras,
- no modificar el contrato público original.

---

### 2. Política de categoría (Strategy Pattern)

Estas clases encapsulan la lógica de negocio de cada tipo de producto y permiten extender nuevas categorías sin tocar la lógica principal del servicio.

#### `com.store.inventory.internal.policy.ReservationPolicy`

Es la interfaz base del patrón Strategy.

Responsabilidades:

- definir el TTL de la reserva,
- definir el límite máximo de unidades por pedido,
- permitir obtener la política según la categoría del producto.

#### `StandardReservationPolicy`

Regla para `STANDARD`:

- TTL: 15 minutos
- Límite por pedido: sin límite

#### `PreOrderReservationPolicy`

Regla para `PRE_ORDER`:

- TTL: 24 horas
- Límite por pedido: sin límite

#### `FlashSaleReservationPolicy`

Regla para `FLASH_SALE`:

- TTL: 5 minutos
- Límite por pedido: 2 unidades

#### `CategoryPolicy`

Clase de acceso para obtener la política asociada a una categoría.

Rol:

- centraliza la resolución de la estrategia por categoría,
- mantiene el servicio principal desacoplado de la implementación de cada política,
- favorece el principio Open/Closed, porque nuevas categorías se agregan sin tocar la lógica del servicio.

---

### 3. Dominio del inventario

#### `com.store.inventory.internal.domain.ReservationStatus`

Enum que modela el estado de una reserva:

- `ACTIVE`
- `CONFIRMED`
- `EXPIRED`

Su función es representar el ciclo de vida de la reserva para un manejo más claro y extensible.

#### `com.store.inventory.internal.domain.Reservation`

Representa la reserva interna del sistema.

Atributos principales:

- `orderId`
- `sku`
- `quantity`
- `expiresAt`
- `status`

Responsabilidad:

- modelar una reserva con sus datos y su vencimiento,
- validar condiciones básicas como id, SKU, cantidad positiva y expiración no nula,
- servir como representación del estado interno para la lógica del servicio.

#### `com.store.inventory.internal.domain.ProductStock`

Es el núcleo del estado por producto.

Responsabilidades:

- administrar el stock total del producto,
- llevar el control de unidades reservadas,
- mantener la lista de reservas activas,
- calcular disponibilidad real,
- gestionar el estado de la alerta de stock bajo,
- encapsular la mutación concurrente con locks por producto.

Características clave:

- usa `AtomicInteger` para cantidades con mutación segura en concurrencia,
- usa `ConcurrentHashMap` para reservas por orden,
- usa `AtomicBoolean` para asegurar que la alerta de bajo stock se emita solo una vez por ciclo de desabastecimiento,
- usa un `ReentrantLock` por producto para serializar cambios críticos sin bloquear todo el servicio.

Esto cumple la exigencia de no usar sincronización global ni generar cuellos de botella.

---

### 4. Servicio principal

#### `com.store.inventory.internal.service.InventoryServiceImpl`

Esta es la implementación que cumple los requerimientos del contrato `InventoryService`.

Funciones principales:

1. Registro de productos
   - `registerProduct(String sku, ProductCategory category)`
   - valida SKU y categoría,
   - registra el producto si todavía no existe,
   - asocia el producto con su política según categoría.

2. Carga de stock
   - `addStock(String sku, int quantity)`
   - invalida cantidades no positivas,
   - agrega unidades al producto,
   - limpia reservas expiradas antes de operar,
   - reevalúa si corresponde una alerta de bajo stock.

3. Reserva de unidades
   - `reserve(String orderId, String sku, int quantity)`
   - valida la orden, el SKU y la cantidad,
   - verifica si la orden ya existe para hacer idempotencia,
   - comprueba que el producto tenga stock suficiente,
   - aplica el límite por pedido según la categoría,
   - fija la expiración según la política del producto,
   - reduce stock disponible solo en la medida correcta,
   - devuelve la reserva creada o la ya existente si sigue activa.

4. Confirmación de pago
   - `confirm(String orderId)`
   - valida que la orden tenga una reserva activa,
   - elimina la reserva del estado activo,
   - confirma la venta,
   - evita que el stock vuelva a estar disponible después de vendida la unidad.

5. Consulta de disponibilidad
   - `available(String sku)`
   - devuelve unidades disponibles reales,
   - libera reservas vencidas antes de calcular,
   - mantiene los valores consistentes con el tiempo actual del `Clock` injectado.

6. Manejo de expiración perezosa
   - elimina reservas vencidas al consultar, reservar o confirmar,
   - evita que un producto conserve reservas viejas en estado activo.

7. Alertas a compras
   - cuando la disponibilidad queda en 5 o menos,
   - se dispara una única alerta por ciclo de desabastecimiento,
   - si el producto vuelve a tener más de 5 unidades disponibles, se resetea el estado de alerta para permitir una nueva notificación en el futuro.

---

### 5. Documentación de decisiones

#### `DECISIONS.md`

Este archivo documenta:

- supuestos de negocio,
- decisiones de diseño,
- qué quedó fuera de alcance,
- qué cambiaría antes de llevar la solución a producción.

Esto ayuda a que el equipo entienda qué decisión técnica se adoptó para cumplir el requerimiento y qué aspectos deberían revisarse al escalar la solución real.

---

### 6. Pruebas

Se agregaron pruebas para cubrir los requisitos de negocio que no estaban en el contrato base:

- idempotencia por `orderId`,
- límite de compra de `FLASH_SALE`,
- expiración de reservas,
- alerta de stock bajo con un solo evento por ciclo,
- comportamiento del flujo real y de concurrencia lógica.

Esto ayuda a validar que la implementación no solo compila, sino que tiene semántica correcta.

---

## Relación con los requerimientos del README

### Requisito: reservas temporales

Se implementó con TTL por categoría y expiración perezosa en el servicio.

### Requisito: liberación de reserva si no paga a tiempo

La lógica de expiración se ejecuta al consultar stock o realizar operaciones relevantes, evitando que reservas vencidas sigan reservando stock.

### Requisito: confirmación de pago

La reserva se confirma cuando se aprueba el pago; las unidades quedan vendidas y no regresan al inventario.

### Requisito: reglas por categoría

Se aplicó el patrón Strategy con políticas específicas para:

- `STANDARD`
- `PRE_ORDER`
- `FLASH_SALE`

### Requisito: alerta de compras

El sistema notifica cuando disponible <= 5 y evita enviar alertas repetidas hasta que el stock vuelva a estar por encima del umbral.

### Requisito: thread-safety y concurrencia

Se usaron estructuras concurrentes y locks por producto para evitar condiciones de carrera y sobreventa del mismo SKU.

### Requisito: idempotencia ante reenvíos

Se usa `orderId` como clave de idempotencia para que una misma orden no reserve stock dos veces si llega repetida.

### Requisito: mantener contrato sin tocar la API

No se modificaron los archivos del paquete `com.store.inventory.api`; la lógica completa quedó dentro de `com.store.inventory.internal`.

---

## Resumen final

La implementación creada cumple con el problema de negocio de inventario y reservas de forma mantenible y extensible:

- usa políticas por categoría,
- calcula disponibilidad sin sobreventas,
- aplica expiración y confirmación de manera segura,
- evita duplicados con idempotencia,
- controla alertas de stock bajo sin repetición innecesaria,
- mantiene la lógica en memoria con un diseño listo para evolucionar.
