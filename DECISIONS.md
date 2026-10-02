# Decisiones de implementaci?n

## Supuestos
- El servicio trabaja en memoria y usa los `Clock` inyectados para hacer las pruebas deterministas.
- Cada `orderId` representa una orden única y se usa como clave idempotente para evitar reservas duplicadas.
- Una reserva agotada por TTL se libera de forma perezosa en las operaciones de lectura y escritura.
- Cuando la disponibilidad cae a 5 o menos, se emite una sola alerta por ciclo de desabastecimiento hasta que el stock vuelva a estar por encima de 5.

## Lo que dejé fuera
- Persistencia en base de datos, ya que el contrato exige almacenamiento en memoria por ahora.
- Integración con más canales de alertas fuera de `StockAlertListener`.
- Reabastecimiento automático o procesos asíncronos; la lógica queda en el servicio de inventario.

## Cambios previstos antes de producción
- Sustituir la capa en memoria por un repositorio persistente con contención real y transacciones.
- Añadir observabilidad y métricas para TTL, reservas expiradas y alertas.
- Extender la política de categorías a un mecanismo de configuración externo para evitar cambios de código al crear nuevas categorías.
