# Cobalto

Aplicación financiera de prueba para Android, iOS y web. La app Flutter y la API Spring Boot comparten PostgreSQL. El saldo se calcula desde un ledger inmutable de doble partida; no hay una columna de saldo editable.

> **Sandbox de desarrollo, no un producto financiero listo para producción.** El crédito inicial de GTQ 100 es ficticio. No se conectan bancos ni redes de pago.

## Alojamiento externo

La computadora del desarrollador no es necesaria para que una versión desplegada funcione. La propuesta cloud es **Cloud Run + Cloud SQL PostgreSQL regional + Secret Manager + bucket privado de Cloud Storage**, con Flutter Web en Cloudflare Pages y despliegue desde GitHub Actions mediante OIDC. El mínimo de Cloud Run evita scale-to-zero, y Cloud SQL tiene backups/PITR; estos componentes administrados tienen costo. **No existe un plan gratuito adecuado para prometer disponibilidad permanente de una aplicación financiera.**

Consulta la comparación de planes, precios orientativos, límites y procedimiento de despliegue en [docs/cloud-hosting.md](docs/cloud-hosting.md). Los recursos iniciales están en `infra/gcp/terraform/`; el workflow realiza CI y despliega solo cuando se configuran sus variables cloud. El sandbox local con Docker descrito abajo es únicamente para desarrollo. Ningún recurso cloud queda desplegado hasta que el operador cree su cuenta, configure facturación, secretos y permisos.

## Estructura

- `cobalto_app/`: cliente Flutter para Android, iOS y web.
- `backend/`: REST API Java 21 / Spring Boot 3.5.7, Spring Security, JWT y Argon2id.
- `backend/src/main/resources/db/migration/`: migraciones versionadas de Flyway.
- `docker-compose.yml`: API y PostgreSQL para desarrollo local.
- `infra/gcp/terraform/`: recursos base cloud, IAM federado, PostgreSQL, secretos y almacenamiento privado.
- `.github/workflows/ci-deploy.yml`: análisis, pruebas y despliegues condicionales.
- `docs/`: arquitectura, decisiones y límites de la primera entrega.

## Requisitos

- Docker Desktop con el motor Linux activo y Docker Compose v2 solo para desarrollo local.
- Flutter stable y Dart compatibles con `cobalto_app/pubspec.yaml`.
- Chrome para ejecutar web; Android SDK y licencias aceptadas para Android.
- Xcode en macOS para compilar iOS.
- Para desplegar cloud, una cuenta Google Cloud con facturación, Terraform y `gcloud`; Cloudflare y GitHub para los despliegues web/API.

Java y Maven locales no son necesarios para iniciar el stack: Docker compila el backend con Java 21. Para desarrollo backend fuera de Docker, instala JDK 21 y Maven 3.9+.

## Iniciar backend y PostgreSQL

En PowerShell, desde la raíz:

```powershell
Copy-Item .env.example .env
# Edita .env y cambia ambos valores de desarrollo.
docker compose up --build
```

La API estará en `http://localhost:8080`; PostgreSQL escucha solo en `127.0.0.1:5432`. Flyway crea el esquema al iniciar. La API requiere `COBALTO_JWT_SECRET` en Base64 con al menos 32 bytes aleatorios. El valor de respaldo de Compose es público y solo sirve para pruebas locales; nunca lo uses fuera del sandbox.

Genera un secreto local aleatorio con:

```powershell
$bytes = [byte[]]::new(32)
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[Convert]::ToBase64String($bytes)
```

Genera por separado `COBALTO_MFA_ENCRYPTION_KEYS` con otros 32 bytes aleatorios y guarda ambos valores distintos en el `.env` local, que no debe subirse al repositorio:

```powershell
$mfaBytes = [byte[]]::new(32)
[Security.Cryptography.RandomNumberGenerator]::Fill($mfaBytes)
[Convert]::ToBase64String($mfaBytes)
```

La API ya no deriva ni reutiliza la clave JWT para cifrar MFA y falla al iniciar si falta la variable MFA. Para rotar, coloca la nueva clave primero en la lista separada por comas y conserva las anteriores para descifrado hasta completar la re-encriptación de los secretos.

## Ejecutar Flutter

```powershell
Set-Location .\cobalto_app
flutter pub get
flutter run -d chrome --dart-define=API_BASE_URL=http://localhost:8080/api
```

Para un emulador Android, configura `API_BASE_URL` con `http://10.0.2.2:8080/api`. En un dispositivo físico, usa la IP LAN del equipo y configura CORS según corresponda. En producción, la API debe servirse exclusivamente detrás de HTTPS.

## Flujo de prueba

1. Registra una cuenta con correo y contraseña de al menos 12 caracteres.
2. La creación del usuario, billetera, crédito ficticio de GTQ 100 y sus dos asientos contables se confirman en una sola transacción.
3. Registra un segundo usuario. Antes de enviar, confirma el destinatario con su correo; la respuesta muestra el nombre y un correo enmascarado.
4. La transferencia requiere fondos suficientes, contraseña, un factor TOTP/código de recuperación si MFA está activo y una clave `Idempotency-Key` UUID.
5. Se registran juntos los asientos, las notificaciones in-app para ambas partes y el evento de auditoría completado. Si falla la operación, el ledger no se publica; el intento/fallo queda auditado aparte.
6. La billetera, el historial paginado, el detalle y el comprobante se consultan desde la API. Todas las transferencias publicadas tienen estado síncrono `COMPLETED`.

### Billeteras y transferencias Sandbox

- `GET /api/wallet`: saldo calculado desde el ledger, movimientos recientes y campos de saldo disponible/retenido. No hay reservas implementadas, por lo que el retenido es siempre `0`.
- `GET /api/recipients/confirm?email=...`: confirma que existe un destinatario distinto del usuario autenticado y devuelve nombre y correo parcialmente oculto.
- `POST /api/transfers`: crea una transferencia GTQ; requiere `Idempotency-Key` UUID y JSON con `recipientEmail`, `amount`, `password` y `mfaFactor` cuando la cuenta usa MFA.
- `GET /api/wallet/movements?limit=20&offset=0`: historial paginado (máximo 100 por página).
- `GET /api/transfers/{transactionId}`: devuelve el detalle/comprobante solo al remitente o destinatario.
- `GET /api/notifications` y `POST /api/notifications/{notificationId}/read`: notificaciones in-app privadas; no se envían correo, SMS ni push.

La transferencia se liquida sincrónicamente o se rechaza; no hay estados pendientes ni retenciones. Idempotencia evita repetir una operación ya publicada y rechaza reutilizar una clave con otra solicitud. Los errores no crean movimientos ni generan saldos negativos. Los eventos de intento, fallo, replay y finalización se conservan en auditoría append-only.

### Reversión controlada Sandbox

- `POST /api/transfers/{transactionId}/reversal`: requiere sesión, permiso `TRANSFER_REVERSAL`, contraseña, factor MFA si está habilitado, `Idempotency-Key` UUID y un `reason` obligatorio de hasta 500 caracteres. La identidad autorizadora siempre se obtiene del JWT, nunca del cuerpo.
- Solo una transferencia publicada (`TRANSFER`, síncrona y `COMPLETED`) puede revertirse una vez. La API crea un movimiento nuevo con asientos exactamente opuestos; no edita ni borra asientos originales. Si los fondos actuales no alcanzan para mantener saldos no negativos, la reversión se rechaza.
- Ledger, auditoría inmutable y notificaciones a ambas partes se confirman en una sola transacción. Las claves de idempotencia se vinculan al autorizador y al contenido; el historial marca la original como `REVERSED` y muestra la reversión en las dos billeteras.
- El permiso no se concede desde la API ni desde la app. Un operador de base de datos con privilegios debe asignarlo en `app_user_permissions`; el rol runtime solo puede consultar esa tabla. Ejemplo: como `cobalto_migrator` o propietario autorizado, inserta `(user_id, 'TRANSFER_REVERSAL', granted_by_user_id)` con UUIDs de usuarios existentes. Para revocar, establece `revoked_at` y `revoked_by_user_id`; no elimines ni modifiques el historial del ledger.
- El permiso se carga desde PostgreSQL en cada solicitud y también se valida en el servicio y en un trigger del ledger. La app solo muestra la acción de reversión a usuarios cuyo perfil informa `canReverseTransfers=true`. El comprobante incluye motivo, transferencia original y el efecto aplicado a la billetera, sin divulgar datos de terceros al autorizador.

## Autenticación y MFA Sandbox

- Los tokens de acceso duran 15 minutos. El refresh token opaco dura 30 días, se almacena como hash en PostgreSQL y rota en cada renovación. Reutilizar un token revocado invalida su familia de sesiones.
- En el perfil, activa MFA con cualquier app TOTP compatible (RFC 6238). Cobalto muestra el secreto una sola vez; confirma el código y guarda los códigos de recuperación, que también son de un solo uso.
- Las rutas de sesión son `POST /api/auth/refresh` y `POST /api/auth/logout`. MFA usa `POST /api/auth/mfa/enrollment`, `POST /api/auth/mfa/enrollment/confirm`, `POST /api/auth/mfa/verify` y `DELETE /api/auth/mfa/enrollment`.
- Los secretos TOTP se cifran con AES-256-GCM y quedan ligados al ID del usuario como AAD.
- `COBALTO_MFA_ENCRYPTION_KEYS` es obligatoria e independiente de `COBALTO_JWT_SECRET`; acepta una lista Base64 separada por comas, cifra con la primera clave y descifra con las anteriores. `MfaSecretCipher.reencrypt` permite re-cifrar un secreto bajo la clave activa; aún no existe un ejecutor por lotes para toda la base.
- Los datos cifrados por versiones anteriores con la clave JWT necesitan conservar temporalmente ese material como clave heredada en el anillo MFA o volver a inscribir MFA. No lo retires hasta migrar/verificar todos los secretos almacenados. Terraform crea el secreto vacío `cobalto-mfa-encryption-keys` y concede `secretAccessor` solo a la identidad runtime; GitHub Actions referencia la versión `latest` al configurar Cloud Run. Antes de desplegar, un operador debe cargar la versión de secreto siguiendo [docs/cloud-hosting.md](docs/cloud-hosting.md). No se incluyen valores de secretos en Terraform ni GitHub.

El TOTP es opcional, no se envían correos/SMS y no hay recuperación de contraseña ni administración de dispositivos de sesión. El backend limita a cinco intentos MFA por desafío y aplica bloqueo temporal por cuenta; el rate limit general sigue siendo local por instancia y no coordina réplicas. No usar estos controles como sustituto de una revisión independiente.

## Verificación

```powershell
Set-Location .\cobalto_app
flutter analyze
flutter test
flutter build web --dart-define=API_BASE_URL=https://sandbox-api.cobalto.example/api
```

El hostname `.example` solo sirve para verificar la compilación; sustitúyelo por la URL HTTPS real del backend desplegado antes de publicar. Las versiones release rechazan `localhost`.

El backend puede probarse con `docker compose up --build`; su Dockerfile compila con Maven y Java 21. Desde `backend`, `mvn test` ejecuta pruebas unitarias y suites de integración sobre PostgreSQL 17.5 embebido en un proceso local temporal; no requiere Docker ni recursos cloud y detiene la base al terminar. La suite aplica Flyway V1–V7 con roles separados de migrador/runtime y verifica que el runtime publique ledger solo mediante funciones SQL acotadas, más atomicidad, equilibrio/inmutabilidad, idempotencia/concurrencia, límites de notificaciones, saldo no negativo, reversión controlada, rollback, MFA e invariantes de auditoría.

## Seguridad y alcance

- Argon2id (Spring Security Argon2) para contraseñas; JWT de acceso de corta duración y token guardado con `flutter_secure_storage`.
- MFA TOTP con secreto cifrado, códigos de recuperación de un solo uso, límite de intentos por desafío y reautenticación con contraseña para activar o desactivar MFA.
- Refresh tokens aleatorios de 256 bits, almacenados solo como SHA-256, rotación bajo bloqueo de fila, revocación en logout y detección de reutilización.
- Acceso autenticado a perfil y billetera; el backend toma la identidad del JWT y no de un ID enviado por el cliente.
- Límite local de solicitudes por IP; la limitación en memoria no coordina réplicas y debe sustituirse por una política distribuida en producción.
- Montos como `NUMERIC`/`BigDecimal`; el runtime no puede insertar ni modificar `ledger_transactions` o `ledger_entries` directamente. V7 expone únicamente funciones SQL de publicación para crédito inicial, transferencia y reversión; triggers diferidos validan los asientos, saldo no negativo, auditoría y notificaciones. El runtime solo puede actualizar `read_at` en notificaciones.
- Transferencias transaccionales con cuentas bloqueadas en orden determinista, fondos comprobados en backend, idempotencia persistida, reautenticación de contraseña y MFA condicional. Notificaciones in-app y auditoría se escriben de forma transaccional con el ledger; los intentos fallidos se auditan fuera de esa transacción.
- El KYC del perfil se reporta `NOT_VERIFIED`. No hay verificación de correo, recuperación de contraseña, administración de dispositivos de sesión, MFA obligatorio ni cifrado/almacenamiento de documentos KYC. V6 implementa una reversión total por transferencia, protegida por permiso DB, reautenticación y auditoría; no hay reversión parcial ni flujo de aprobación de dos personas. QR, comercios, pagos, tarjetas tokenizadas y panel administrativo siguen pendientes. Las notificaciones son solo in-app, sin correo/SMS/push. El saldo retenido es cero hasta implementar reservas. El rate limit y antifraude distribuido, observabilidad de negocio y pruebas de carga siguen pendientes. El monitoreo cloud inicial solo aporta salud/disponibilidad del servicio cuando se configura su hostname y canal.
- No se recopilan datos de tarjetas. No hay integraciones bancarias ni afirmación de seguridad absoluta.

Antes de producción hacen falta evaluación regulatoria/local, revisión independiente de seguridad, gestión de secretos, TLS, respaldos y recuperación, observabilidad, límites antifraude, pruebas concurrentes, controles operativos y proveedores autorizados.
