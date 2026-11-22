# Alojamiento cloud: evaluación y despliegue

Revisado el **21 de noviembre de 2026**. Precios, cuotas y condiciones cambian; confirmar los términos vigentes y la elegibilidad comercial antes de contratar. Las cuotas gratuitas no son acuerdos de disponibilidad ni autorizaciones para custodiar dinero o documentos regulados.

## Alternativas evaluadas

| Servicio | Gratis verificado | Límites y consideraciones | Uso recomendado |
|---|---|---|---|
| [Cloudflare Pages](https://developers.cloudflare.com/pages/platform/limits/) | Sí. Hasta 500 builds al mes, un build concurrente, 20 minutos por build, 20.000 archivos y 25 MiB por archivo en Free. | Alojamiento estático; no ejecuta Spring Boot ni incluye PostgreSQL. No ofrece por sí solo SLA financiero. | Flutter Web estático y CDN. |
| [Cloudflare R2](https://developers.cloudflare.com/r2/pricing/) | 10 GB-mes, un millón de operaciones de clase A y 10 millones de clase B al mes; egreso gratuito. | La cuota solo cubre Standard. Se factura el exceso y deben configurarse acceso privado y controles de retención. | Alternativa de bajo costo para objetos no sensibles; no almacenar KYC aquí en esta etapa. |
| [Supabase](https://supabase.com/pricing) | 500 MB de PostgreSQL, 1 GB de archivos, 5 GB de egreso y hasta dos proyectos activos. | Los proyectos Free se pausan tras una semana inactivos. Pro cuesta desde US$25/mes, incluye 8 GB por proyecto y copias diarias de 7 días; no se pausa por inactividad. Supabase es migrable por ser PostgreSQL, pero su Auth/Storage añade acoplamiento. | Buena alternativa administrada para prototipos; no seleccionada para mantener Spring Security/JWT como autoridad única. |
| [Neon](https://neon.com/pricing) | 100 CU-horas por proyecto, 1 GB por proyecto, 5 GB de egreso; escala a cero tras 5 minutos. | El plan gratuito está dirigido a prototipos y proyectos pequeños; una pausa automática no cumple el requisito de respuesta permanente. Launch factura por uso y permite desactivar scale-to-zero. No promete una modalidad gratuita de disponibilidad permanente. | Desarrollo y pruebas con PostgreSQL; no como base de producción gratuita. |
| [Render Free](https://render.com/docs/free) | 750 horas gratuitas de instancia al mes por workspace. | El servicio web duerme tras 15 minutos sin tráfico y tarda alrededor de un minuto en reactivarse; la base gratuita vence 30 días después de crearla. Render recomienda Free para pruebas, no producción. | Demostraciones desechables, no para operaciones financieras. |
| [Oracle Cloud Always Free](https://www.oracle.com/cloud/free/) | Cómputo, almacenamiento y otros servicios Always Free sujetos a cuotas y capacidad regional. | Exige tarjeta para validar la cuenta; está sujeto a disponibilidad de capacidad. Cuentas inactivas por 30 días o más pueden considerarse abandonadas y suspenderse o terminarse. No es PostgreSQL administrado gratuito: operar Postgres en una VM implica parcheo, backups, recuperación y guardias propios. | Laboratorio si se acepta administrar la VM; no es la recomendación de producción. |
| [GitHub Free](https://github.com/pricing) | Repositorios públicos/privados ilimitados y 2.000 minutos CI/CD al mes para GitHub Free. | CI/CD y control de código; no hospeda la base de datos ni mantiene un backend de producción. Revisar cuotas de Actions antes de activar despliegues frecuentes. | Repositorio y automatización CI/CD. |
| [Firebase Cloud Messaging](https://firebase.google.com/pricing) | FCM no tiene costo por mensajería según la tabla de Firebase; requiere un servidor de confianza que envíe los mensajes. | Entrega push, pero no sustituye notificaciones persistentes del backend ni historial de auditoría. Las credenciales de servidor nunca van en Flutter. | Adaptador futuro de notificaciones push. |
| [Google Cloud Run](https://cloud.google.com/run/pricing) + [Cloud SQL](https://cloud.google.com/sql/pricing) | Cloud Run aplica cuotas gratuitas de cómputo a uso elegible. Cloud SQL no tiene una cuota gratuita continua; hay créditos iniciales temporales para cuentas elegibles. | Mantener una instancia mínima activa de Cloud Run evita depender de scale-to-zero, pero es facturable. Cloud SQL cobra cómputo, almacenamiento, backups y red. La alta disponibilidad regional cobra una segunda instancia. | Opción seleccionada para el backend y PostgreSQL administrado; producción siempre activa no es gratis. |

### Condiciones comerciales

Los términos revisados contemplan clientes empresariales o cuentas de organizaciones en Cloudflare, Supabase, Neon/Databricks, GitHub y Google/Firebase; no encontré en esas fuentes una prohibición comercial general que permita concluir que un plan gratuito sea adecuado para esta fintech. **Eso no equivale** a aprobación para datos financieros regulados, ni confirma por sí solo los requisitos de residencia, auditoría o tratamiento que exija Guatemala. Firebase declara expresamente que el uso de sus servicios debe relacionarse con una actividad profesional/comercial. Render advierte expresamente que su plan gratuito no debe usarse en producción. Oracle sujeta Always Free a su acuerdo cloud, cuotas y a las reglas de inactividad. Los términos concretos dependen del servicio, región, cuenta y plan contratado.

Fuentes legales: [Cloudflare Self-Serve Subscription Agreement](https://www.cloudflare.com/terms/), [Supabase Terms](https://supabase.com/terms), [Neon Product Schedule](https://neon.com/platform-terms), [Render Free limitations](https://render.com/docs/free), [Oracle Free Tier FAQ](https://www.oracle.com/cloud/free/), [GitHub Terms](https://docs.github.com/en/site-policy/github-terms/github-terms-of-service) y [Firebase Terms](https://firebase.google.com/terms). No se identificó un veto comercial uniforme que convierta los planes gratuitos en un alojamiento adecuado para una fintech; la ausencia de esa prohibición tampoco es autorización regulatoria ni SLA. Validar contrato, DPA, soporte, región de datos y requisitos regulatorios con asesoría legal antes de producción.

### Decisión

**Cloud Run + Cloud SQL para PostgreSQL + Secret Manager + Cloud Storage privado** forman el plano de backend en Google Cloud. Cloud SQL usa una instancia regional con backups y recuperación a un punto en el tiempo; Cloud Run mantiene un mínimo de una instancia. La conexión Java usa Cloud SQL Socket Factory y la identidad de servicio de Cloud Run, no una IP de la computadora del desarrollador.

**Cloudflare Pages** entrega Flutter Web y HTTPS estático. **GitHub Actions** ejecuta análisis y pruebas y despliega desde `main` mediante Workload Identity Federation (OIDC), sin una llave de cuenta de servicio guardada en GitHub. Las aplicaciones Android/iOS y Web usan la misma URL HTTPS de Cloud Run. Firebase Cloud Messaging puede añadirse después detrás de un adaptador; no se habilita hasta implementar el registro de dispositivos y el flujo de notificaciones.

Es una arquitectura portable: Java/Spring, Docker, JDBC, PostgreSQL, Flyway y objetos por interfaz. Cambiar de proveedor requiere migrar infraestructura, secretos, almacenamiento y DNS; no se promete migración instantánea.

Cloud SQL regional y Cloud Run reducen riesgos de indisponibilidad de una sola zona, pero no constituyen una garantía de operación continua ni recuperación multirregional. Las credenciales, despliegues reales, presupuestos, alertas, restauraciones y objetivos RTO/RPO deben configurarse y probarse en el proyecto cloud del operador. No desplegar ni custodiar dinero real hasta completar la revisión regulatoria y de seguridad en Guatemala.

### Coste orientativo

Cloud SQL no es gratuito de forma permanente. Con los precios publicados para `us-central1`, una configuración `db-custom-1-3840` de una sola zona equivale aproximadamente a **US$49/mes de CPU y RAM** (1 vCPU × US$0,0413/h + 3,75 GiB × US$0,007/GiB-h × 730 h), antes de disco, backups, transferencia e impuestos. La modalidad regional de alta disponibilidad factura recursos en dos zonas y aproximadamente duplica esa porción de cómputo/RAM. Cloud Run con una instancia mínima, Cloud Storage, registro, egress y el dominio agregan costos variables. Confirmar con la calculadora de Google Cloud para región, carga, impuestos y retención reales; crear alertas de presupuesto antes de desplegar.

## Despliegue inicial

### 1. Crear los recursos base de Google Cloud

1. Crea un proyecto dedicado, activa facturación y selecciona `us-central1` para acercar la API y la base. No compartas el proyecto con cargas no relacionadas.
2. Instala Terraform 1.6+, `gcloud`, Java 21 y Maven 3.9+ para tareas administrativas; Flutter Web se compila en GitHub Actions. Docker local es opcional: no participa en la operación en la nube.
3. Autentica `gcloud` como operador con permisos de infraestructura; habilita `storage.googleapis.com` para poder inicializar el backend remoto de Terraform. Crea manualmente el bucket remoto de estado con nombre globalmente único, acceso uniforme, prevención de acceso público y versionado. Guarda el estado y limita IAM a los operadores.
4. Copia `infra/gcp/terraform/terraform.tfvars.example` como `terraform.tfvars` y configura un proyecto dedicado, repositorio GitHub y nombre único de bucket de documentos. El archivo real está ignorado por Git.
5. Desde `infra/gcp/terraform`, ejecuta:

   ```powershell
   terraform init -backend-config="bucket=TU_BUCKET_DE_ESTADO" -backend-config="prefix=cobalto/production"
   terraform validate
   terraform plan -var-file="terraform.tfvars"
   terraform apply -var-file="terraform.tfvars"
   ```

   Terraform crea Cloud SQL PostgreSQL 17 regional, un límite de almacenamiento, backups/PITR de siete días, Artifact Registry, bucket privado, secretos sin valores, cuentas de servicio y la federación OIDC restringida al repositorio GitHub configurado y a `refs/heads/main`. Protege `main` con revisiones y checks requeridos; la restricción OIDC no reemplaza esas protecciones. No destruyas el estado, bucket, secretos ni base desde una cuenta de operador sin un plan de recuperación aprobado.

6. En Secret Manager, carga valores para `cobalto-app-db-password`, `cobalto-migration-db-password`, `cobalto-jwt-secret` y `cobalto-mfa-encryption-keys`. Genera valores aleatorios distintos y guarda sus primeras versiones directamente en Secret Manager usando el mecanismo seguro del operador; no los incluyas en Terraform, GitHub variables, comandos guardados ni logs. El JWT debe contener al menos 32 bytes aleatorios. `cobalto-mfa-encryption-keys` debe contener un anillo de claves Base64 separado por comas e independiente del JWT: la primera clave cifra y las anteriores se conservan para descifrado hasta completar la rotación.
7. Configura una contraseña administrativa de PostgreSQL desde Cloud SQL y úsala solo para crear los usuarios de aplicación y migración; no la guardes en código ni en GitHub. En la base `cobalto`, concede al migrador permiso de crear objetos en el esquema y configura privilegios por defecto restrictivos. La migración V7 deja al runtime solo permisos explícitos de lectura/escritura de aplicación; no concede escritura contable directa:

   ```sql
   REVOKE CREATE ON SCHEMA public FROM PUBLIC;
   GRANT USAGE, CREATE ON SCHEMA public TO cobalto_migrator;
   GRANT CONNECT ON DATABASE cobalto TO cobalto_app, cobalto_migrator;
   ```

   Como `cobalto_migrator`, configura privilegios por defecto para que las tablas nuevas partan solo de lectura:

   ```sql
   ALTER DEFAULT PRIVILEGES FOR ROLE cobalto_migrator IN SCHEMA public
     GRANT SELECT ON TABLES TO cobalto_app;
   ALTER DEFAULT PRIVILEGES FOR ROLE cobalto_migrator IN SCHEMA public
     REVOKE INSERT, UPDATE, DELETE ON TABLES FROM cobalto_app;
   ALTER DEFAULT PRIVILEGES FOR ROLE cobalto_migrator IN SCHEMA public
     REVOKE ALL ON SEQUENCES FROM cobalto_app;
   ```

   V7 revoca al runtime `INSERT`, `UPDATE` y `DELETE` amplios, y concede `SELECT` sobre el esquema. Solo permite `INSERT` en tablas de identidad, billeteras, sesiones/MFA, notificaciones y auditorías; limita las actualizaciones de autenticación a las tablas de sesión/perfil necesarias, concede `UPDATE(read_at)` en notificaciones y deja sin privilegios DML `app_user_permissions`. No concede privilegios runtime sobre secuencias. En particular, `ledger_transactions` y `ledger_entries` quedan en solo lectura y las funciones de V7 son el único canal runtime para registrar crédito inicial, transferencias y reversiones. V7 concede `EXECUTE` de esas funciones solo a `cobalto_app` y revoca ejecución a `PUBLIC`.

   `app_user_permissions` solo se administra con credenciales de operador/migración. Asigna `TRANSFER_REVERSAL` únicamente a operadores aprobados mediante esa conexión privilegiada y revoca el permiso con `revoked_at`/`revoked_by_user_id`, sin borrar el historial de concesión. `transfer_reversal_audits` permite lectura/inserción al runtime y bloquea cambios mediante trigger. Revisa los privilegios de futuras tablas y valida los permisos efectivos usando `cobalto_app` después de aplicar Flyway.

   La API requiere `COBALTO_MFA_ENCRYPTION_KEYS` como una lista Base64 independiente de `COBALTO_JWT_SECRET`. El secreto Terraform es solo un contenedor vacío; el operador agrega una versión desde un archivo protegido localmente, sin mostrar su contenido:

   ```powershell
   gcloud secrets versions add cobalto-mfa-encryption-keys --data-file="RUTA_PROTEGIDA_DEL_ARCHIVO"
   ```

   Terraform concede `roles/secretmanager.secretAccessor` sobre ese secreto únicamente a `cobalto-api-runtime`; ni el migrador ni el deployer pueden leer su valor. El workflow configura Cloud Run para consumir la versión `latest`, sin descargarla a GitHub Actions. La API no tiene fallback a la clave JWT. La rotación debe conservar claves antiguas hasta completar y verificar la re-encriptación de los secretos MFA.
8. El bucket KYC se crea privado con acceso público bloqueado y **sin permiso IAM de objetos para la aplicación** hasta implementar autorización, cifrado/documentación, URLs de corta duración, auditoría y política legal de retención. No subas datos reales de identidad en esta entrega.
9. Añade las variables de GitHub Actions indicadas abajo, y crea el proyecto Cloudflare Pages. Configura el dominio web y el origen HTTPS de API. Solo entonces el push a `main` despliega ambos componentes.

El API puede iniciarse en la primera corrida de Actions después de subir los secretos. Crea un canal de notificación en Cloud Monitoring y configura `api_hostname` con el hostname público de la API y `notification_channel_ids` con el ID del canal en `terraform.tfvars`; vuelve a aplicar para crear el uptime check HTTPS y la alerta ante fallos. Configura alertas de consumo de presupuesto y una prueba periódica de restauración.

### 2. Variables de GitHub

Configura estas **Actions variables** (no secretos):

| Variable | Valor |
|---|---|
| `GCP_PROJECT_ID` | ID del proyecto de Google Cloud |
| `GCP_REGION` | `us-central1` |
| `GCP_WIF_PROVIDER` | Salida `github_workload_identity_provider` de Terraform |
| `GCP_DEPLOYER_SERVICE_ACCOUNT` | Salida `github_deployer_service_account` |
| `GCP_RUNTIME_SERVICE_ACCOUNT` | Salida `runtime_service_account` |
| `GCP_MIGRATOR_SERVICE_ACCOUNT` | Salida `migrator_service_account` |
| `GCP_ARTIFACT_REPOSITORY` | Nombre `cobalto-api` |
| `GCP_CLOUD_SQL_INSTANCE` | Salida `cloud_sql_connection_name` |
| `GCP_DB_USERNAME` | `cobalto_app` |
| `COBALTO_WEB_ORIGIN` | Origen HTTPS exacto de Pages, por ejemplo `https://tu-proyecto.pages.dev` |
| `COBALTO_API_BASE_URL` | URL HTTPS de Cloud Run terminada en `/api` |
| `CLOUDFLARE_ACCOUNT_ID` | ID de la cuenta Cloudflare |
| `CLOUDFLARE_PAGES_PROJECT` | Nombre del proyecto de Pages |

Configura como **Actions secret** `CLOUDFLARE_API_TOKEN`, limitado al permiso de despliegue de Pages para esa cuenta. Google se autentica con OIDC; no crees ni almacenes claves JSON de cuentas de servicio. El workflow solo despliega API/Web en pushes a `main` cuando están configuradas sus variables; en PR ejecuta validaciones sin desplegar.

### 3. URLs de API y configuración local

- Cloud Run recibe HTTPS y sirve `/api`. Configura `COBALTO_WEB_ORIGIN` con dominios explícitos; no uses comodines en producción.
- Flutter release **falla al iniciar** si `API_BASE_URL` no es una URL HTTPS pública. Para desarrollo local continúa usando el valor predeterminado de localhost; para Web/Android/iOS de producción, usa `--dart-define=API_BASE_URL=https://<servicio-cloud-run>/api`.
- La base y el backend no escuchan en la computadora del desarrollador. El compose existente es solo una opción de desarrollo.
- El workflow publica la imagen Docker por SHA de commit en Artifact Registry. El backend usa perfil `cloud`, Cloud SQL Java Connector, secretos del gestor de secretos y `/actuator/health`.

### 4. Operación, monitoreo y recuperación

- La base tiene backups automáticos y PITR por siete días; también debe probarse una restauración en un proyecto de recuperación independiente y exportarse periódicamente según los RPO/RTO y requisitos locales. Un backup no verificado no demuestra que la recuperación funcione.
- Terraform crea un Cloud Monitoring uptime check HTTPS y una alerta de indisponibilidad cuando se informa el hostname y el canal de notificación. Configura además alertas para errores HTTP/fallos de base de datos y consumo de presupuesto. Los registros de Spring Boot se escriben a stdout para Cloud Logging; nunca registres contraseñas, JWT, CVV ni documentos de KYC.
- Mantén límites IAM mínimos, rotación y revocación de secretos, actualizaciones de Java/dependencias y revisión de audit logs. Revisa residencia, tratamiento de datos, DPA, condiciones comerciales, SLA y requisitos regulatorios con asesoría local.
- Cloud SQL regional ayuda con fallas zonales, pero no provee por sí sola copia de seguridad fuera de la región. Definir recuperación ante desastre regional, cifrado con claves administradas por el cliente, retenciones, pruebas antifraude y seguridad independiente antes de operar fondos reales.
- El rate limit actual usa Caffeine en memoria por instancia; no coordina las réplicas de Cloud Run. Antes de tráfico comercial, migrarlo a una política distribuida y dimensionada (por ejemplo, un servicio gestionado con límites y failover) y probar abuso/fuerza bruta bajo carga.
