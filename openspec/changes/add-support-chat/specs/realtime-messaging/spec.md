# Spec Delta

## Purpose

Intercambio de mensajes en tiempo real entre el cliente y el bot de una conversación, con orden idéntico para todos los que la ven (el cliente en una o varias pestañas y los admins observadores), sin duplicados visibles y sin pérdida de mensajes cuando alguien se desconecta y vuelve a conectarse.

## ADDED Requirements

### Requirement: Canal en tiempo real por conversación
El sistema SHALL ofrecer un canal bidireccional y persistente por conversación al que se conectan el cliente (rol `CUSTOMER`, puede enviar), los admins identificados como agentes (rol `AGENT`, solo pueden enviar mientras la conversación esté asignada a ellos) y observadores (rol `OBSERVER`, solo reciben). Cada mensaje SHALL identificar a su remitente como `CUSTOMER`, `BOT`, `AGENT` o `SYSTEM`. Los mensajes nuevos SHALL aparecer a todos los conectados sin recargar la página, normalmente en menos de un segundo desde que se aceptan.

#### Scenario: Intercambio básico
- **WHEN** el cliente y un admin observador están conectados a la misma conversación y el cliente envía "Hola"
- **THEN** el admin ve "Hola" sin recargar la página, marcado como enviado por el cliente

#### Scenario: Conexión a una conversación inexistente
- **WHEN** alguien intenta conectarse a un identificador de conversación que no existe
- **THEN** el sistema cierra el canal indicando que la conversación no existe

#### Scenario: Aislamiento entre conversaciones
- **WHEN** un mensaje se envía en la conversación A
- **THEN** nadie conectado solo a la conversación B lo recibe

#### Scenario: El observador no puede enviar
- **WHEN** una conexión con rol `OBSERVER` intenta enviar un mensaje
- **THEN** el sistema responde un error de solo lectura y no persiste ni difunde nada

#### Scenario: Agente sin la conversación asignada
- **WHEN** una conexión `AGENT` de "Marta" envía un mensaje en una conversación que atiende el bot o que está asignada a "Luis"
- **THEN** el sistema responde un error de "no asignada" y no persiste ni difunde nada

### Requirement: Orden total asignado por el servidor
El sistema SHALL asignar a cada mensaje aceptado, sea del cliente, del bot o del sistema, un número de secuencia (`seq`) por conversación que empieza en 1, crece de uno en uno y no tiene huecos ni repeticiones, incluso cuando llegan mensajes concurrentes. El `seq` SHALL ser el único criterio de orden. Las marcas de tiempo del cliente no SHALL influir en el orden. Todas las vistas SHALL mostrar los mensajes ordenados por `seq`.

#### Scenario: El cliente escribe mientras el bot responde
- **WHEN** el bot está generando la respuesta al mensaje `seq=4` y el cliente envía otro mensaje antes de que esa respuesta se acepte
- **THEN** el mensaje del cliente recibe `seq=5` y la respuesta del bot `seq=6` (o al revés, según cuál se acepte primero), y el cliente y los observadores ven exactamente el mismo orden

#### Scenario: Mismo cliente en dos pestañas
- **WHEN** el cliente tiene su conversación abierta en dos pestañas y envía un mensaje desde cada una casi al mismo tiempo
- **THEN** el servidor les asigna `seq` consecutivos y ambas pestañas (y los observadores) muestran los dos mensajes en el mismo orden

#### Scenario: Llegada fuera de orden al navegador
- **WHEN** una vista recibe el mensaje con `seq=8` antes que el de `seq=7`
- **THEN** la vista muestra el 7 antes que el 8

#### Scenario: Concurrencia masiva sin huecos
- **WHEN** se envían 50 mensajes concurrentes a la misma conversación
- **THEN** quedan persistidos con los `seq` 1 a 50 exactamente, sin repetidos ni faltantes

### Requirement: Confirmación (ACK) de cada envío
Cada mensaje enviado por el cliente SHALL llevar un identificador único generado por el cliente (`clientMessageId`). Cuando el mensaje queda persistido, el sistema SHALL responder al emisor con un ACK que incluye ese `clientMessageId` y el `seq` asignado. Hasta recibir el ACK, la vista del emisor SHALL mostrar el mensaje como pendiente.

#### Scenario: ACK tras persistir
- **WHEN** el cliente envía un mensaje con `clientMessageId=X`
- **THEN** recibe un ACK con `clientMessageId=X` y el `seq` asignado, y su vista deja de marcar el mensaje como pendiente

#### Scenario: Contenido inválido
- **WHEN** el cliente envía un mensaje vacío o de más de 2000 caracteres
- **THEN** el sistema responde con un error asociado a ese `clientMessageId` y no persiste ni difunde el mensaje

### Requirement: Entrega al menos una vez sin duplicados visibles
Los envíos SHALL ser idempotentes por (`conversación`, `clientMessageId`): si el mismo mensaje llega más de una vez (por ejemplo, un reintento tras una reconexión), el sistema SHALL persistirlo una sola vez y responder con un ACK que lleve el mismo `seq` original, sin volver a difundirlo ni volver a pedir una respuesta al bot. Las vistas SHALL descartar cualquier mensaje con un `seq` que ya muestran.

#### Scenario: Reintento de un envío ya persistido
- **WHEN** el cliente reenvía el mensaje `clientMessageId=X` porque se desconectó antes de recibir su ACK, y el servidor ya lo había persistido con `seq=12`
- **THEN** el servidor responde con un ACK con `seq=12`, no crea otro mensaje, el bot no responde otra vez, y nadie ve el mensaje dos veces

#### Scenario: Mensaje duplicado entregado a la vista
- **WHEN** una vista recibe dos veces el mensaje con `seq=5` (una por el historial y otra en vivo)
- **THEN** lo muestra una sola vez

### Requirement: Reanudación sin pérdida tras reconectar
Al abrir o reabrir el canal, el participante SHALL poder indicar el último `seq` que tiene (`lastSeq`). El sistema SHALL entregarle primero todos los mensajes persistidos con `seq > lastSeq`, en orden, y después los mensajes en vivo, sin huecos entre ambas fases. Si una vista detecta un hueco en la secuencia, SHALL pedir los mensajes faltantes al historial. Las vistas SHALL reconectar automáticamente con espera creciente (backoff), y la app del usuario SHALL reenviar los mensajes propios que sigan sin ACK.

#### Scenario: El cliente se desconecta mientras el bot responde
- **WHEN** el cliente pierde la conexión con `lastSeq=10` justo después de enviar una pregunta, el bot responde con `seq=11` y el cliente reconecta
- **THEN** el cliente recibe el mensaje 11 del bot y luego sigue recibiendo los nuevos en vivo

#### Scenario: Mensaje aceptado durante la ventana de reconexión
- **WHEN** se acepta un mensaje nuevo justo mientras el servidor reenvía el historial a un participante que se reconecta
- **THEN** el participante recibe ese mensaje exactamente una vez y en su posición correcta

#### Scenario: Envío mientras se está desconectado
- **WHEN** el cliente escribe un mensaje mientras su conexión está caída
- **THEN** el mensaje se muestra como pendiente y se envía automáticamente al reconectar, recibiendo su ACK y su `seq`

#### Scenario: Hueco detectado en vivo
- **WHEN** una vista con último `seq=4` recibe en vivo el `seq=7`
- **THEN** pide al historial los mensajes con `seq` mayor que 4 y termina mostrando 5, 6 y 7 en orden

### Requirement: Indicador efímero de "el bot está escribiendo"
Mientras el bot genera una respuesta, el sistema SHALL avisar a los conectados a esa conversación con una señal efímera que no se persiste, no consume `seq` y no se reenvía al reanudar. La señal SHALL apagarse cuando llega la respuesta del bot o cuando falla la generación.

#### Scenario: Indicador visible durante la generación
- **WHEN** el cliente envía una pregunta y el bot empieza a generar la respuesta
- **THEN** el cliente y los observadores ven "el asistente está escribiendo…" hasta que aparece la respuesta

#### Scenario: El indicador no forma parte del historial
- **WHEN** un participante reconecta después de que el bot respondiera
- **THEN** recibe la respuesta del bot, pero ningún indicador de "escribiendo" antiguo

### Requirement: Modo de la conversación visible en tiempo real
Todas las vistas de una conversación SHALL conocer quién la atiende: el asistente automático o un agente humano, con su nombre. El sistema SHALL informarlo al terminar la sincronización de cada conexión y SHALL avisar en vivo cada cambio, sin que sea necesario recargar. La vista del cliente SHALL mostrar, mientras atiende una persona, un aviso "Estás hablando con <nombre> (agente de soporte)", y SHALL distinguir los mensajes del agente de los del bot.

#### Scenario: El cliente ve que lo atiende una persona
- **WHEN** Luis toma la conversación mientras Ana tiene el chat abierto
- **THEN** Ana ve, sin recargar, el mensaje del sistema y el aviso "Estás hablando con Luis (agente de soporte)"

#### Scenario: Reconexión durante la atención humana
- **WHEN** Ana recarga la página mientras la conversación está asignada a Luis
- **THEN** tras sincronizar ve de nuevo el aviso de que la atiende Luis

### Requirement: Estado de conexión visible
Las vistas SHALL mostrar si la conexión está activa, reconectando o caída, para que el participante sepa por qué un mensaje sigue pendiente o por qué no llegan mensajes nuevos.

#### Scenario: Pérdida de conexión
- **WHEN** se cae el canal de un participante
- **THEN** su vista muestra "reconectando" hasta que el canal se restablece y la sincronización termina
