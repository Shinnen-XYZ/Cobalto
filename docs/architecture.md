# Arquitectura inicial

```text
Flutter (Android / iOS / Web)
    │                   │
    │ Cloudflare Pages  │ HTTPS + REST + JWT
    │ (solo Web)        ▼
    └──────────────► Cloud Run (Spring Boot)
                            │ Cloud SQL Connector + identidad de servicio
                            ▼
                  Cloud SQL PostgreSQL regional
                  Flyway / ledger de doble partida / auditoría

Secret Manager: password de DB, firma JWT y anillo de claves MFA
Cloud Storage: bucket privado; IAM de objetos aún no habilitado
GitHub Actions ── OIDC Workload Identity Federation ──► despliegues cloud
```

Las versiones Android/iOS y Web consumen la misma API HTTPS. Cloud Run mantiene una instancia mínima activa; Cloud SQL tiene alta disponibilidad regional, backups automáticos y PITR. El servicio local `docker-compose.yml` es solo para desarrollo y no forma parte del camino productivo.

La topología se provisiona en `infra/gcp/terraform/`; Cloud Run se despliega desde `.github/workflows/ci-deploy.yml`. Precios y alternativas gratuitas evaluadas están documentados en [cloud-hosting.md](cloud-hosting.md). La base administrada, cómputo siempre activo, copias, registro y transferencia generan costos; esta arquitectura no constituye una garantía de operación continua ni un sistema listo para operar dinero real.

## Límites deliberados

- La API es la autoridad para identidad, fondos, autorización e idempotencia.
- Cada movimiento crea una transacción y asientos de débito/crédito con importes decimales; triggers diferidos rechazan transacciones sin asientos o sin débitos/créditos equilibrados y de una sola moneda. El saldo es una suma del ledger.
- V7 revoca al rol runtime la inserción y modificación directa de las tablas contables. El crédito inicial, las transferencias y las reversiones se publican mediante funciones PostgreSQL `SECURITY DEFINER` con `search_path` fijo, validación de actor/moneda/importe/idempotencia y permisos `EXECUTE` explícitos. Las funciones crean asientos atómicamente dentro de la transacción Spring existente; las comprobaciones diferidas siguen exigiendo auditoría y notificaciones para transferencias/reversiones.
- El ledger es append-only mediante triggers de base de datos. V4 enlaza una reversión con su transacción original, limita cada original a una sola reversión y valida al confirmar que los nuevos asientos sean exactamente opuestos; V6 añade al ledger el ID del usuario autorizador sin cambiar el actor original ni registros históricos.
- `GTQ` es la única moneda operativa por ahora. La columna de moneda y el ledger admiten monedas adicionales, pero no se realiza conversión.
- El crédito inicial se registra contra una cuenta de emisión `SYSTEM` para conservar igualdad contable; no representa dinero real.
- Los refresh tokens son opacos, rotatorios y se guardan como hash; MFA TOTP cifra sus secretos y almacena códigos de recuperación solo como hashes.
- `COBALTO_MFA_ENCRYPTION_KEYS` es un anillo Base64 obligatorio e independiente de `COBALTO_JWT_SECRET`; la primera clave cifra y las anteriores permiten descifrar durante la rotación. Los secretos TOTP usan AES-256-GCM con AAD por usuario. Terraform crea el secreto `cobalto-mfa-encryption-keys`, concede lectura solo a la identidad runtime y GitHub Actions lo referencia como secreto de Cloud Run. La versión debe ser cargada por un operador antes del despliegue.
- Las transferencias están limitadas al Sandbox GTQ: el servicio confirma el destinatario, reautentica con contraseña y exige un factor MFA adicional si está habilitado; valida fondos, bloquea ambas billeteras en orden determinista y crea los asientos con idempotencia en una sola transacción. Las respuestas son síncronas (`COMPLETED` o error), sin estado pendiente ni reserva.
- El saldo se deriva de `ledger_entries`. La API informa saldo retenido `0`, ya que no existe mecanismo de reservas. Un trigger diferido serializa los cambios de las billeteras y rechaza cualquier resultado negativo.
- La migración V5 crea notificaciones in-app y eventos de auditoría append-only. Las notificaciones de remitente y destinatario y el evento de finalización comparten la transacción financiera; los intentos, fallos y replays se auditan en transacciones separadas. V7 concede al runtime solo `UPDATE(read_at)` en notificaciones; el servicio usa una actualización explícita de esa columna y la base impide modificar otros campos.
- La migración V6 añade permisos `TRANSFER_REVERSAL` con concesión/revocación auditables. No hay endpoint para otorgarlos: un operador autorizado de PostgreSQL administra la tabla, mientras que el rol runtime tiene solo `SELECT`. El filtro JWT carga permisos actuales desde la base, la configuración REST y el servicio los validan, y un trigger impide crear asientos de reversión sin permiso activo.
- `POST /api/transfers/{transactionId}/reversal` exige permiso, contraseña, factor MFA cuando se usa y motivo. Bloquea la transferencia original, permite una única reversión total e idempotente y confirma en la misma transacción los asientos opuestos, el registro inmutable `transfer_reversal_audits` y las notificaciones de ambas billeteras. El destinatario debe conservar fondos suficientes; no hay reversión parcial ni aprobación de doble control.
- El detalle de la operación permite a las dos partes consultar el comprobante de reversión; el autorizador puede consultar su propio comprobante sin recibir el correo ni la identidad financiera de las partes. El historial conserva la transferencia original como `REVERSED` y añade el movimiento inverso en cada billetera.
- `GET /api/recipients/confirm`, `POST /api/transfers`, `GET /api/transfers/{transactionId}`, `GET /api/wallet/movements` y `GET /api/notifications` exponen confirmación, transferencia, comprobante/historial y notificaciones. El detalle se limita a las partes de la transferencia; los identificadores ajenos se ocultan como no encontrados.
- La suite de integración usa PostgreSQL 17.5 embebido solo para pruebas, aplica Flyway V1–V7 con roles separados de migrador/runtime y comprueba permisos efectivos, transacciones, asientos, idempotencia/concurrencia, saldo no negativo, rollback, autorizaciones e invariantes de notificaciones/auditoría. El servidor vive en un directorio temporal local y no necesita Docker ni recursos cloud.
- Las integraciones de tarjetas, bancos, KYC, QR y pagos se añadirán detrás de adaptadores hacia proveedores autorizados; las notificaciones externas (correo, SMS y push) siguen pendientes.

## Próximos controles de producción

El TOTP es opcional; faltan verificación de correo, recuperación de contraseña, administración de sesiones/dispositivos, reversión parcial, aprobación de dos personas, reservas de fondos, notificaciones push/SMS/correo, rate limits distribuidos, reconciliación y alertas antifraude, auditoría general de acceso, retención y borrado conforme a regulación. El cifrado ofrece una operación de re-encriptación por secreto, no un ejecutor por lotes. La integración cloud referencia el secreto en Terraform y el workflow, pero requiere que el operador cargue y rote sus versiones antes de desplegar.
