# Proposal

## Why

El ejercicio pide un chat de soporte construido desde cero en el que un cliente y un agente intercambien mensajes en tiempo (casi) real, sin recargar la página, y que resuelva el problema clásico de **orden y entrega** de mensajes: duplicados por reconexión, mensajes casi simultáneos y pérdida de mensajes al desconectarse. El repositorio hoy está vacío (solo un README), así que este change define el producto mínimo y las decisiones que el ejercicio exige justificar.

El agente es un **bot** respaldado por Claude (vía Spring AI), restringido a responder **solo sobre un tema configurable** desde la app de administración. El valor inicial es **bicicletas**. El ejercicio lo permite ("o incluso un bot simple") y además convierte el orden y la entrega en un problema real: el bot responde de forma asíncrona mientras el cliente puede seguir escribiendo. Los mensajes se **persisten** en PostgreSQL. No es un simple broadcast: la base de datos es la fuente de verdad del orden y permite reconstruir la conversación tras una reconexión.

## What Changes

- **Backend nuevo** (`backend/`): Java 26 + Spring Boot 4 **WebFlux** (reactivo), R2DBC PostgreSQL y arquitectura hexagonal con el mismo layout que `pragma/03-reto-reactivo`.
  - Endpoint **WebSocket** `/ws/conversations/{id}` para enviar y recibir mensajes en tiempo real (el cliente escribe; el admin observa).
  - API REST `/api/v1/conversations` para crear conversaciones, listarlas (vista admin) y leer el historial paginado por secuencia.
  - **Secuencia por conversación asignada por el servidor** (`seq` monotónico, sin huecos) como único criterio de orden.
  - **Idempotencia** por `clientMessageId` con restricción única en BD, y **ACK** con el `seq` asignado.
  - **Reanudación**: al reconectar, el participante envía su último `seq` y el servidor reenvía lo posterior desde la BD antes de seguir en vivo.
  - **Bot de soporte** con Spring AI: responde a cada mensaje del cliente, **solo sobre el tema configurado** (bicicletas por defecto). Cualquier otro tema recibe la negativa configurada, decidida por el servidor. Su respuesta entra por el mismo canal ordenado e idempotente que cualquier mensaje.
  - **Dos proveedores de LLM con respaldo**: primero Anthropic (Claude) y, si no tiene llave, falla o tarda demasiado, OpenAI. Solo si ninguno responde, el bot avisa que no está disponible, sin romper el chat.
  - **Configuración del alcance del bot** versionada en BD (`GET/PUT /api/v1/bot-scope`): tema, descripción, subtemas permitidos y texto de la negativa. El backend los inserta en una plantilla de prompt fija que conserva las reglas de seguridad.
- **Frontend nuevo** (`frontend/`): workspace Angular 22 (standalone, signals, zoneless) con **dos apps** y una librería compartida, siguiendo las fuentes y el sistema visual de `leancore-fintech`:
  - `user-app` (**puerto 4200**): el cliente abre (o recupera) su conversación y chatea con el bot.
  - `admin-app` (**puerto 4300**) con dos pestañas: **Conversaciones** (lista todas las activas, que pueden ser muchas a la vez, y permite seguir cualquiera en vivo y **tomarla**: el bot deja de responder, se avisa al cliente que habla con una persona y el agente responde desde el admin, hasta devolverla al asistente) y **Alcance del bot** (editar el tema permitido y ver su historial de versiones).
  - `chat-core` (librería): cliente de chat compartido con reconexión, deduplicación y orden por `seq`.
- **Infraestructura**: `docker-compose.yml` con `postgres` (puerto **5432 publicado** en el host para conectarse con un cliente SQL), `backend`, `user-app` y `admin-app`, cada app con su nginx y proxy de `/api` y `/ws`. `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL`, `OPENAI_API_KEY` y `OPENAI_MODEL` se configuran en `.env`. Todo se levanta con `docker compose up --build`.
- **Documentación** en el README con las 4 decisiones justificadas: tiempo real, orden, reconexión y despliegue en producción, más cómo se configura y se limita el alcance del bot.
- Sin autenticación: el cliente se identifica con un nombre visible y el admin no requiere login.

## Capabilities

### New Capabilities
- `support-conversations`: ciclo de vida de una conversación de soporte: crearla desde la user-app, recuperarla al volver, listarla y seguirla desde la admin-app, **tomarla y devolverla al asistente** (modo bot / modo humano), y consultar su historial persistido.
- `realtime-messaging`: envío y recepción de mensajes en tiempo real con orden total por conversación (secuencia del servidor), entrega al menos una vez con idempotencia y deduplicación, ACKs, reanudación sin pérdida tras reconectar y observadores de solo lectura.
- `support-bot`: agente automático respaldado por un LLM que responde a los mensajes del cliente, restringido estrictamente al tema configurado, con la negativa configurada fuera de alcance, una sola respuesta por mensaje y un respaldo cuando el LLM no está disponible.
- `bot-scope-config`: configuración versionada del alcance del bot desde la app de administración (tema, descripción, subtemas y negativa), con bicicletas como valor inicial, y efecto desde la siguiente respuesta del bot.

### Modified Capabilities
<!-- Ninguna: el proyecto no tiene specs previas. -->

## Impact

- **Código**: todo es nuevo: `backend/` (Gradle, Spring Boot WebFlux), `frontend/` (workspace Angular con `user-app`, `admin-app` y `chat-core`) y `docker-compose.yml` + `docker/` en la raíz.
- **Dependencias**: `spring-boot-starter-webflux`, `spring-boot-starter-data-r2dbc`, `r2dbc-postgresql`, `spring-boot-starter-validation`, **Spring AI (starters de Anthropic y OpenAI)**, Lombok/MapStruct y springdoc; Testcontainers PostgreSQL para las pruebas de integración. Frontend: Angular 22 + Vitest.
- **Servicios externos**: API de Anthropic (Claude) y API de OpenAI como respaldo; cada una requiere su llave y tiene **costo por uso**. Ambas son opcionales en tiempo de ejecución: sin ninguna llave el chat funciona y el bot avisa que no está disponible.
- **Base de datos**: PostgreSQL 17 con las tablas `conversation`, `message` y `bot_scope`, expuesta en `localhost:5432`.
- **Puertos**: user-app 4200, admin-app 4300, backend 8080 y Postgres 5432.
- **Fuera de alcance**: cola o asignación automática de agentes, transferencia entre agentes e indicador de "escribiendo" del agente, alcance distinto por conversación, edición libre del prompt, autenticación robusta, adjuntos, confirmaciones de lectura y el despliegue real (solo se documenta).
