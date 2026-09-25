# leancore-chat · Chat de soporte

Chat de soporte construido desde cero. Un **cliente** escribe desde la app de usuario, un **bot** (Claude, con OpenAI de respaldo, vía Spring AI) le responde en tiempo real, restringido a un **tema configurable** (bicicletas por defecto), y los **administradores** siguen en vivo todas las conversaciones y configuran ese tema. Los mensajes se guardan en PostgreSQL: la base de datos es la fuente de verdad del orden, no un simple broadcast.

- **Backend:** Java 26, Spring Boot 4.1 **WebFlux** (reactivo), R2DBC PostgreSQL, Spring AI 2.0 (Anthropic y OpenAI), arquitectura hexagonal (`domain` / `application` / `infrastructure`).
- **Frontend:** workspace Angular 22 (standalone, signals, zoneless) con dos apps, `user-app` y `admin-app`, y una librería compartida, `chat-core`, con el sistema visual de `leancore-fintech`.
- **Todo en un comando:** `docker compose up --build`.

```
 user-app :4200 ----+                        +--> PostgreSQL :5432 (conversation, message, bot_scope)
 (cliente)          |  /api (REST)           |
                    +--> nginx --> backend --+
 admin-app :4300 ---+  /ws  (WebSocket)  :8080|
 (observa y configura)                       +--> Anthropic (Claude) -> OpenAI de respaldo, según las llaves
```

## Cómo levantarlo

Requisito: Docker con Compose.

```bash
cp .env.example .env        # opcional: ANTHROPIC_API_KEY, OPENAI_API_KEY, modelos, contraseña y puertos
docker compose up --build
```

| Servicio | URL / puerto | Notas |
|---|---|---|
| App de usuario | <http://localhost:4200> | Escribir y chatear con el bot |
| App de administración | <http://localhost:4300> | Pestañas **Conversaciones** (seguir, tomar y responder como agente) y **Alcance del bot** |
| Backend | <http://localhost:8080/swagger-ui.html> | REST documentado; WebSocket en `/ws/conversations/{id}` |
| PostgreSQL | `localhost:5432` | Usuario `chat`, base `chat_db`, contraseña `chat` (o `DB_PASSWORD`) |

- **Dos proveedores con respaldo:** responde **Claude** (`ANTHROPIC_API_KEY`, modelo `ANTHROPIC_MODEL`, por defecto `claude-sonnet-5`). Si no hay llave de Anthropic, si su API falla (por ejemplo, por saldo agotado) o si tarda más de 12 s, responde **OpenAI** (`OPENAI_API_KEY`, modelo `OPENAI_MODEL`, por defecto `gpt-5-mini`). El log del backend muestra al arrancar qué proveedores están activos.
- **Quién respondió:** en el visor del admin cada respuesta del bot lleva una etiqueta con el proveedor y el modelo (por ejemplo `openai · gpt-5-mini`); también queda en `message.bot_provider` y `message.bot_model`. La app del cliente no la muestra, y las API keys nunca se exponen.
- **Sin ninguna llave el chat funciona igual:** los mensajes se ordenan y guardan, y el bot responde con un mensaje de sistema, "El asistente no está disponible en este momento".
- **Puertos ocupados:** si otro proyecto usa alguno (por ejemplo `leancore-fintech` usa 5432 y 8080), cámbialos en `.env` con `DB_HOST_PORT`, `BACKEND_HOST_PORT`, `USER_APP_PORT` y `ADMIN_APP_PORT`.
- **Borrar los datos:** `docker compose down -v`.

### Conectarse a la base de datos

La base queda publicada a propósito, para inspeccionar el orden real de los mensajes:

```bash
psql -h localhost -p 5432 -U chat -d chat_db
```

```sql
select seq, sender_role, reply_to_seq, bot_scope_version, content
  from message where conversation_id = '<id>' order by seq;
select version, topic, subtopics, created_at from bot_scope order by version;
```

### Desarrollo local sin contenedores de aplicación

```bash
docker compose up -d postgres                          # solo la base
cd backend && ./gradlew bootRun                        # backend en :8080 (DB_PORT si cambiaste el puerto)
cd frontend && npm ci && npm run start:user            # :4200, proxy de /api y /ws a :8080
cd frontend && npm run start:admin                     # :4300
```

## Cómo probarlo

```bash
# Backend: unitarias + integración contra PostgreSQL real (Testcontainers, requiere Docker).
# El bot se reemplaza por uno falso y determinista: no se llama a ningún LLM.
cd backend && ./gradlew test

# Evaluación del alcance del bot contra las APIs reales (cuesta unos centavos por corrida). Evalúa cada
# proveedor que tenga llave, porque cualquiera de los dos puede ser el que responda.
# Comprueba que ~10 preguntas de bicicletas quedan dentro del tema, que ~10 ajenas y 3 intentos de
# prompt injection quedan fuera, y que con el tema "cafeteras" se invierte. Sin llave, la suite se omite.
cd backend && ANTHROPIC_API_KEY=sk-ant-... OPENAI_API_KEY=sk-... ./gradlew llmTest

# Frontend: chat-core, user-app y admin-app (Vitest).
cd frontend && npm ci && npm test
```

### Guion de prueba manual

1. Abre <http://localhost:4200> en **dos pestañas**. En la primera escribe tu nombre e inicia el chat. La segunda, al recargarla, abre la misma conversación, porque el id se guarda en el navegador.
2. Abre <http://localhost:4300>, selecciona la conversación y déjala a la vista: es de solo lectura y no tiene campo de envío.
3. Envía un mensaje desde cada pestaña casi al mismo tiempo. Las tres vistas muestran los dos mensajes en el **mismo orden** (el número `#seq` de cada burbuja) y el bot responde cada uno una sola vez.
4. Pregunta "¿Qué es un avión?": la respuesta es exactamente la negativa configurada. Pregunta algo de bicicletas: responde con normalidad (si hay llave).
5. En DevTools pon una pestaña en **Offline**, escribe dos mensajes (quedan "pendiente…") mientras la otra pestaña sigue escribiendo, y vuelve a **Online**. La pestaña reconecta, recibe lo que se perdió y envía sus pendientes. No aparecen duplicados ni faltantes.
6. Compara con la base: `select seq, sender_role, content from message order by seq` coincide con lo que muestran las tres vistas.
7. En **Alcance del bot** cambia el tema a "cafeteras" y guarda. Sin reiniciar nada, la siguiente pregunta de bicicletas recibe la negativa de cafeteras, y en la base esa respuesta queda con la nueva `bot_scope_version`.
8. Deja solo `OPENAI_API_KEY` (o pon una llave de Anthropic inválida): el bot sigue respondiendo con OpenAI, y el log muestra `Bot provider anthropic unavailable ..., trying openai`.
9. Repite sin ninguna llave: cada mensaje recibe el aviso del sistema de "no disponible" y el chat sigue funcionando.
10. **Tomar la conversación:** en el admin escribe tu nombre de agente, abre la conversación y pulsa **Tomar conversación**. El cliente ve "<nombre> se unió a la conversación. Ahora estás hablando con una persona." y el aviso "Estás hablando con <nombre> (agente de soporte)". Responde desde el admin: el bot ya no contesta nada de lo que escriba el cliente.
11. Con otro nombre de agente en otra pestaña del admin, intenta tomar la misma conversación: aparece "La conversación la atiende <nombre>".
12. Pulsa **Devolver al asistente**: el cliente ve "El asistente automático retoma la conversación." y el bot responde solo lo que el cliente escriba después, no lo que quedó sin responder mientras atendía la persona.

## Decisiones

### 1. Mecanismo de tiempo real: WebSocket

Uso **WebSocket nativo** sobre Spring WebFlux (sin STOMP), en `/ws/conversations/{id}?role=CUSTOMER|AGENT|OBSERVER&name=...&lastSeq=N`.

- Es **bidireccional en una sola conexión**: por el mismo canal viajan los envíos, las confirmaciones (ACK), los mensajes entrantes y el indicador "el asistente está escribiendo". Eso hace natural el protocolo de ACK + reanudación.
- WebFlux/Netty mantiene muchas conexiones abiertas por instancia sin bloquear hilos.
- **SSE** solo va del servidor al cliente: los envíos irían por POST y los ACK por otro camino. **Polling** tiene más latencia y más carga. **STOMP** añade un broker y un protocolo que aquí no aportan. El polling sí se usa donde basta: la lista de conversaciones del admin se refresca cada 3 s.

Protocolo (JSON):

```
cliente -> servidor   { "type": "SEND", "clientMessageId": "<uuid>", "content": "..." }
servidor -> cliente   MESSAGE { message: { seq, clientMessageId, senderRole, senderName, content, replyToSeq, ... } }
                      ACK     { clientMessageId, seq, createdAt }
                      SYNCED  { lastSeq, mode, agentName }  fin del reenvío del historial + quién atiende
                      TYPING  { active }             efímero: sin seq, no se guarda ni se reenvía
                      MODE    { mode, agentName }    efímero: pasó a atender una persona (HUMAN) o el bot (BOT)
                      ERROR   { clientMessageId, code: VALIDATION | NOT_FOUND | READ_ONLY | NOT_ASSIGNED | INTERNAL, message }
```

El rol y el nombre se fijan al conectar y el servidor los sella en cada mensaje: nadie puede escribir como `BOT` o `SYSTEM`. Un observador que intenta enviar recibe `READ_ONLY`.

### 2. Orden: secuencia por conversación asignada por el servidor

Cada mensaje aceptado (del cliente, del bot o del sistema) recibe un **`seq` por conversación: 1, 2, 3…, sin huecos ni repetidos**. Es el **único** criterio de orden, y todas las vistas ordenan por él.

```sql
-- una sola transacción
UPDATE conversation SET last_seq = last_seq + 1, ... WHERE id = $1 RETURNING last_seq;  -- bloquea la fila
INSERT INTO message (conversation_id, seq, client_message_id, ...) VALUES (...);
```

- El `UPDATE … RETURNING` **bloquea la fila de la conversación**: dos mensajes casi simultáneos (el cliente escribiendo mientras el bot responde, o el mismo cliente en dos pestañas) se serializan, y cada uno obtiene `n` y `n+1` según su orden de commit. Todos los participantes ven ese mismo orden.
- La generación del bot (lenta) ocurre **fuera** de la transacción: solo el INSERT final de su respuesta toma el lock.
- Si la transacción falla, el rollback deshace también el incremento: la secuencia **no tiene huecos**, y eso permite que el cliente detecte con exactitud cuándo le falta un mensaje.
- Se descartaron el timestamp del cliente (relojes distintos, cada vista podría ordenar diferente), el timestamp del servidor (empates y saltos de reloj; no permite detectar huecos), un `BIGSERIAL` global (ordena, pero deja huecos en los rollbacks) y un contador en memoria (se pierde al reiniciar y no escala).

**Entrega: al menos una vez + idempotencia = efecto de exactamente una vez.** El cliente genera un `clientMessageId` (UUID) por mensaje y lo reutiliza en cada reintento. La restricción única `(conversation_id, client_message_id)` hace que un reintento devuelva el **ACK con el `seq` original**, sin guardar otra fila, sin volver a difundirlo y sin que el bot responda otra vez. La respuesta del bot usa un id **determinista** derivado de la pregunta (UUID v5), así que el bot nunca contesta dos veces la misma pregunta, aunque su trabajo se dispare de nuevo por un reinicio o por dos réplicas. En el navegador, los mensajes se guardan en un mapa `seq → mensaje`, y cualquier entrega repetida (historial + vivo, eco propio, reenvío) se descarta.

```
Cliente                         Servidor                         BD
  | SEND(id=X)  ------------------> | tx: seq=12, insert X ------> |
  |      x-- la conexión cae antes del ACK                          |
  | (reconecta con lastSeq=11)      |                              |
  | <----- MESSAGE seq=12 (reenvío del historial) --------------- |
  | SEND(id=X) (reintento) -------> | X ya existe -> ACK seq=12     |
  | <------------ ACK(X, 12) ------ | (sin difusión, sin bot)       |
  pendiente X -> confirmado #12, mostrado una sola vez
```

### 3. Desconexión y reconexión: no se pierden mensajes

- **El cliente** reconecta solo, con espera creciente y aleatoria (0,5 s → 1 s → 2 s …, máximo 10 s), y reabre el socket con **`lastSeq`**, el último `seq` contiguo que tiene. Mientras tanto muestra "Reconectando…" y deja como "pendiente…" lo que el usuario escriba. Tras `SYNCED` reenvía los pendientes en su orden original. Si el servidor ya los tenía, la idempotencia los absorbe.
- **El servidor** entrega primero todo lo guardado con `seq > lastSeq` (paginado desde la BD), luego `SYNCED` y después lo nuevo en vivo. Para que no se pierda un mensaje que llegue *entre* la lectura del historial y la suscripción en vivo, **se suscribe al canal en vivo antes de leer el historial**, guarda en buffer lo que llega y descarta al final lo que el historial ya entregó (por `seq`).
- **Red de seguridad:** si una vista recibe `seq=7` teniendo hasta el 4, espera ~300 ms (dos commits consecutivos pueden publicarse en orden invertido) y pide `GET /messages?afterSeq=4` para completar. Como la secuencia es densa, el hueco se detecta con exactitud.
- El servidor envía un **ping cada 25 s**, y nginx usa un `proxy_read_timeout` alto: la conexión sobrevive a periodos sin tráfico.
- **Qué se pierde:** nada de lo que el servidor aceptó. Solo se perderían mensajes todavía *pendientes* (sin ACK) si el usuario cierra la pestaña, porque el outbox vive en memoria.
- Si el servidor se cae después de aceptar una pregunta y antes de que el bot responda, al arrancar (o cuando alguien vuelve a conectarse a esa conversación) busca las preguntas sin respuesta (`reply_to_seq`) y las responde una sola vez.

### 4. Despliegue en producción (propuesta, no desplegado)

```
             +----------------------------+
 browser --> | CDN (user-app y admin-app) |
    |        +----------------------------+
    | wss / https
    v
 +-----------------------------+      +---------------------------------+
 | Load balancer L7 con WS     |----->| backend (N replicas, stateless) |
 | (ALB / Cloud LB / Ingress)  |      | WebFlux + Netty + cola del bot  |
 | idle timeout > ping (25 s)  |      +----------------+----------------+
 +-----------------------------+                       |
                              +------------------------+------------------------+
                              v                        v                        v
                  +----------------------+  +------------------------+  +------------------+
                  | Postgres gestionado  |  | Pub/Sub: Redis o       |  | Anthropic ->     |
                  | (RDS / Cloud SQL)    |  | Postgres LISTEN/NOTIFY |  | OpenAI respaldo  |
                  | fuente de verdad     |  | fan-out entre replicas |  |                  |
                  +----------------------+  +------------------------+  +------------------+
```

- **Backend:** contenedores sin estado en un orquestador gestionado (ECS Fargate, Cloud Run con WebSockets o Kubernetes), con N réplicas y autoescalado por conexiones abiertas y CPU. Netty soporta decenas de miles de sockets por instancia. Los estáticos van a un CDN.
- **Escalar el tiempo real:** el cliente y los admins pueden quedar en réplicas distintas. La difusión en memoria de este entregable está detrás de un puerto (`IMessageBroadcastPort`) y se reemplaza por **Postgres `LISTEN/NOTIFY`** (sin infraestructura nueva) o **Redis Pub/Sub**. El broker puede ser *best effort*: lo que pierda lo recupera la reanudación desde la BD. **No hacen falta sticky sessions.**
- **Bot con varias réplicas:** la respuesta se genera en la réplica que aceptó la pregunta, y el id determinista garantiza una sola respuesta aunque dos réplicas la generen. Para absorber picos, se puede pasar a una cola durable (tabla con `FOR UPDATE SKIP LOCKED` o SQS). El LLM tendría la llave en un gestor de secretos, timeouts, circuit breaker, límites de uso y de costo, y métricas de tokens.
- **Base de datos:** Postgres gestionado con réplica, backups y PITR, pooling de conexiones, y archivado o particionado del historial frío.
- **Operación:** `wss://` terminado en el balanceador y *graceful shutdown*: al desplegar, los clientes reconectan con `lastSeq` a otra réplica sin perder mensajes. Métricas de conexiones, latencia de ACK, latencia del bot y tasa de negativas.
- **Seguridad** (fuera del ejercicio): JWT en el handshake, admin detrás de SSO con un rol para editar el alcance, validación del origen y base de datos no expuesta.

## Tomar la conversación (agente humano)

Desde el admin, un agente puede tomar una conversación que atiende el bot, responder él mismo y devolvérsela después al asistente.

```
                 admin: "Tomar conversación"   (POST /api/v1/conversations/{id}/takeover)
   +--------+  -------------------------------------------------------->  +----------------------+
   |  BOT   |                                                               | HUMAN (agente Daniel)|
   +--------+  <--------------------------------------------------------  +----------------------+
                 admin: "Devolver al asistente" (POST /api/v1/conversations/{id}/release)
```

- **Aviso al cliente con su propio `seq`:** cada cambio publica un mensaje `SYSTEM` ordenado como cualquier otro, más un frame `MODE` para que las vistas actualicen quién atiende. Si alguien reconecta, lo sabe por `SYNCED`.
- **Uno solo gana:** tomar es un `UPDATE ... WHERE mode='BOT'` condicional; de dos tomas simultáneas una recibe **409** con el nombre de quien la atiende. Solo el agente asignado puede escribir (`ERROR NOT_ASSIGNED` para los demás) y devolverla.
- **El bot calla sin carreras:** el mismo `UPDATE` que asigna el `seq` exige `mode='BOT'` para las respuestas del bot. Si el bot estaba generando cuando el agente tomó la conversación, su respuesta se descarta sin ocupar número de orden, así que no hay huecos.
- **Al devolverla** se guarda `bot_resume_after_seq`: el bot responde solo los mensajes posteriores, no los que se escribieron mientras atendía la persona.
- **Nombre del agente:** se escribe en el admin y se recuerda en ese navegador. Como el resto del ejercicio, no hay autenticación.

## El bot y su alcance

- **Configurable desde el admin** (pestaña *Alcance del bot*): tema, descripción, subtemas permitidos y texto de la negativa. Cada guardado crea una **versión nueva e inmutable**, que aplica desde la **siguiente** respuesta del bot, sin reiniciar. Las respuestas ya dadas no cambian, y cada respuesta guarda la versión con la que se generó (`message.bot_scope_version`). Volver a una versión anterior = cargarla en el formulario y guardarla como versión nueva. El valor inicial (versión 1) es **bicicletas**.
- **No se edita el prompt completo:** el backend inserta esos campos, como datos entre comillas y en una sola línea, en una **plantilla fija** (`backend/src/main/resources/prompts/scoped-support.st`). La plantilla conserva siempre las reglas de seguridad: los mensajes del usuario son datos y no instrucciones, cómo tratar temas mixtos y saludos, el idioma y la extensión.
- **Proveedores:** un adaptador genérico por proveedor (misma plantilla, misma salida, mismo mapeo de errores) y uno de respaldo que los recorre en orden. Pasa al siguiente cuando no hay llave, hay un error o se superan 12 s. Una respuesta ilegible **no** se reintenta con otro proveedor: se responde la negativa. Si un proveedor falla, durante 30 s se consulta al final, para que una caída no sume 12 s a cada mensaje.
- **La negativa la decide el servidor, no el LLM:** el modelo devuelve `{ "inScope": boolean, "reply": string }`. Si `inScope` es falso, o si la respuesta no se puede interpretar, el backend publica **exactamente** la negativa configurada. Por eso "¿Qué es un avión?" o "ignora tus instrucciones…" terminan en un texto que el modelo no controla.
- **Límites:** temperatura 0.2, máximo 400 tokens de salida, contexto de los últimos 20 mensajes, respuesta recortada a 2000 caracteres, 20 s de timeout y máximo 8 llamadas simultáneas al LLM. Las preguntas de una misma conversación se responden en orden, de a una; conversaciones distintas no se bloquean entre sí.
- **Límite conocido:** la clasificación `inScope` sigue siendo del modelo, así que una pregunta ajena bien disfrazada del tema podría colarse. Se mitiga con la plantilla estricta, la temperatura baja y la suite `llmTest`.

## Limitaciones conocidas (aceptadas por el ejercicio)

- Si el agente cierra el admin sin devolver la conversación, ésta sigue asignada a él (la lista muestra quién la atiende); no hay liberación automática por inactividad.
- Sin autenticación: cualquiera con la admin-app ve todas las conversaciones, puede tomarlas con cualquier nombre de agente y puede cambiar el alcance (las versiones permiten ver y revertir cada cambio); cualquiera con el UUID de una conversación puede unirse a ella.
- Los mensajes pendientes viven en memoria de la pestaña: cerrarla antes del ACK los pierde.
- La difusión en vivo es en memoria (una instancia); con varias réplicas hay que usar el adaptador de LISTEN/NOTIFY o Redis descrito arriba.
- La base de datos está publicada con credenciales de desarrollo: solo para uso local.
