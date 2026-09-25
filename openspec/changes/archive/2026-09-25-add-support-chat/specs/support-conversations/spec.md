# Spec Delta

## Purpose

Gestiona las conversaciones de soporte entre un cliente y el bot: crearlas desde la app del usuario, recuperarlas al volver, listarlas y seguirlas desde la app de administración, y consultar su historial persistido.

## ADDED Requirements

### Requirement: El cliente inicia una conversación
El sistema SHALL permitir que un cliente cree una conversación de soporte desde la app del usuario indicando un nombre visible no vacío (máximo 60 caracteres). La conversación SHALL recibir un identificador único generado por el servidor y quedar persistida con su fecha de creación.

#### Scenario: Creación exitosa
- **WHEN** un cliente envía la solicitud de crear conversación con el nombre "Ana"
- **THEN** el sistema responde con el identificador de la nueva conversación, el nombre del cliente, la fecha de creación y un último `seq` igual a 0

#### Scenario: Nombre inválido
- **WHEN** un cliente intenta crear una conversación con el nombre vacío o de más de 60 caracteres
- **THEN** el sistema rechaza la solicitud con un error de validación y no crea ninguna conversación

### Requirement: El cliente recupera su conversación al volver
La app del usuario SHALL recordar en el navegador el identificador de su conversación y, al recargar la página o volver, SHALL reabrir esa misma conversación con su historial completo en lugar de crear una nueva. El cliente SHALL poder iniciar una conversación nueva de forma explícita.

#### Scenario: Recarga de la página
- **WHEN** un cliente que ya tiene una conversación con 5 mensajes recarga la página
- **THEN** ve la misma conversación con los 5 mensajes en el mismo orden, sin haber creado otra conversación

#### Scenario: Conversación recordada que ya no existe
- **WHEN** el identificador guardado en el navegador no corresponde a ninguna conversación
- **THEN** la app del usuario olvida ese identificador y le pide su nombre para iniciar una nueva conversación

#### Scenario: Nueva conversación explícita
- **WHEN** el cliente elige "Nueva conversación"
- **THEN** la app olvida la conversación anterior y crea una nueva tras pedir el nombre

### Requirement: El admin lista las conversaciones
La app de administración SHALL mostrar, en su pestaña "Conversaciones", todas las conversaciones, que pueden ser muchas y simultáneas, ordenadas por actividad más reciente primero. Cada elemento SHALL incluir el nombre del cliente, el número de mensajes (último `seq`), un extracto del último mensaje con su remitente, la fecha de la última actividad y quién la atiende (el asistente o el nombre del agente). La lista SHALL refrescarse sin recargar la página (al menos cada pocos segundos).

#### Scenario: Lista con varias conversaciones activas
- **WHEN** existen las conversaciones A (último mensaje hace 1 minuto) y B (último mensaje hace 10 minutos)
- **THEN** la lista muestra A antes que B, cada una con su número de mensajes y el extracto de su último mensaje

#### Scenario: Nueva conversación aparece sin recargar
- **WHEN** un cliente crea una conversación mientras el admin tiene abierta su vista
- **THEN** la nueva conversación aparece en la lista del admin sin que este recargue la página

### Requirement: El admin sigue una conversación en vivo
La app de administración SHALL permitir abrir cualquier conversación y ver sus mensajes (del cliente, del bot, del agente y del sistema) en tiempo real, en el mismo orden que ve el cliente. Mientras el admin no haya tomado la conversación, su vista SHALL ser de solo lectura: sin campo de envío. Varios admins SHALL poder seguir la misma conversación a la vez, y un admin SHALL poder cambiar de conversación sin recargar.

#### Scenario: Seguimiento en vivo
- **WHEN** el admin tiene abierta la conversación de "Ana" y Ana envía un mensaje que el bot responde
- **THEN** el admin ve ambos mensajes aparecer sin recargar, en el mismo orden que Ana

#### Scenario: Cambio de conversación
- **WHEN** el admin pasa de la conversación A a la B
- **THEN** deja de recibir los mensajes de A y ve el historial completo de B seguido de sus mensajes en vivo

### Requirement: El admin toma la conversación
Desde el visor, un admin SHALL poder tomar una conversación que atiende el asistente, identificándose con un nombre de agente (1 a 60 caracteres, recordado en su navegador). Al tomarla, la conversación SHALL pasar a modo humano, asignada a ese agente, y el sistema SHALL publicar en ella un mensaje `SYSTEM` ordenado como cualquier otro que avise al cliente que ahora habla con una persona, con el nombre del agente. Desde ese momento el bot no SHALL responder, y solo el agente asignado SHALL poder escribir. Si dos admins intentan tomarla a la vez, SHALL ganar uno solo; el otro SHALL recibir un conflicto y ver quién la atiende. Volver a tomarla con el mismo agente no SHALL tener efecto.

#### Scenario: Toma exitosa
- **WHEN** el admin "Luis" toma la conversación de "Ana"
- **THEN** Ana y todos los que la siguen ven el mensaje del sistema "Luis se unió a la conversación. Ahora estás hablando con una persona.", y Luis ve un campo de envío

#### Scenario: Dos admins la toman a la vez
- **WHEN** "Luis" y "Marta" pulsan "Tomar conversación" casi al mismo tiempo
- **THEN** solo uno queda asignado y publica el aviso; el otro recibe un conflicto y ve "Atendida por <nombre>"

#### Scenario: El agente responde
- **WHEN** Luis, asignado, escribe "Hola Ana, ¿en qué te ayudo?"
- **THEN** Ana lo recibe en tiempo real como mensaje del agente, con su propio `seq`, y el bot no responde

### Requirement: El agente devuelve la conversación al asistente
El agente asignado SHALL poder devolver la conversación al asistente. Al hacerlo, la conversación SHALL volver a modo bot, el sistema SHALL publicar un mensaje `SYSTEM` que avise al cliente que vuelve a atenderle el asistente automático, y el bot SHALL responder solo los mensajes del cliente posteriores a ese aviso, no los que se escribieron mientras atendía la persona. Solo el agente asignado SHALL poder devolverla.

#### Scenario: Devolución
- **WHEN** Luis devuelve la conversación al asistente
- **THEN** Ana ve "El asistente automático retoma la conversación.", y su siguiente pregunta la responde el bot

#### Scenario: Preguntas hechas mientras atendía la persona
- **WHEN** Ana escribió dos mensajes que Luis no contestó y luego Luis devuelve la conversación
- **THEN** el bot no responde esos dos mensajes; solo responde lo que Ana escriba después

#### Scenario: Devolver una conversación ajena
- **WHEN** Marta intenta devolver una conversación asignada a Luis
- **THEN** el sistema responde un conflicto y la conversación sigue asignada a Luis

### Requirement: Historial persistido y paginado por secuencia
El sistema SHALL persistir cada mensaje aceptado y SHALL permitir leer el historial de una conversación ordenado por `seq` ascendente, pidiendo solo los mensajes con `seq` mayor que un valor dado (`afterSeq`) y con un límite de resultados (por defecto 100, máximo 500).

#### Scenario: Lectura incremental
- **WHEN** se piden los mensajes de una conversación con `afterSeq=3` y la conversación tiene mensajes con `seq` 1 a 7
- **THEN** el sistema devuelve exactamente los mensajes con `seq` 4, 5, 6 y 7, en ese orden

#### Scenario: Conversación inexistente
- **WHEN** se pide el historial de un identificador de conversación que no existe
- **THEN** el sistema responde "no encontrado"

#### Scenario: Los mensajes sobreviven al reinicio del servidor
- **WHEN** el servidor se reinicia después de intercambiar mensajes
- **THEN** el historial de la conversación sigue disponible, completo y en el mismo orden
