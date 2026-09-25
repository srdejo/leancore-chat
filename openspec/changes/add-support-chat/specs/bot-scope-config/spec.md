# Spec Delta

## Purpose

Permite configurar desde la app de administración el tema al que se restringe el bot de soporte (tema, descripción, subtemas permitidos y texto de la negativa), con historial de versiones y bicicletas como valor inicial.

## ADDED Requirements

### Requirement: Configuración de alcance activa con valor inicial
El sistema SHALL mantener exactamente una configuración de alcance activa para el bot, global a todas las conversaciones, compuesta por: nombre del tema, descripción, lista de subtemas permitidos y texto de la negativa. En una instalación nueva, la configuración activa SHALL ser la versión 1 con el tema "bicicletas", sus subtemas (mecánica y mantenimiento, repuestos y componentes, tallas y ajuste, tipos de bicicleta, accesorios y equipo, rutas y técnica, seguridad al rodar) y la negativa "Solo puedo ayudarte con temas de bicicletas. Tu pregunta está fuera de mi alcance."

#### Scenario: Instalación nueva
- **WHEN** se consulta la configuración de alcance en un sistema recién levantado
- **THEN** el sistema devuelve la versión 1 con el tema "bicicletas", sus subtemas y su negativa

#### Scenario: Consulta desde la app de administración
- **WHEN** el admin abre la pestaña "Alcance del bot"
- **THEN** ve el tema, la descripción, los subtemas, el texto de la negativa, el número de versión y la fecha de la configuración activa

### Requirement: Editar el alcance crea una nueva versión
El admin SHALL poder guardar una nueva configuración de alcance desde la app de administración. Cada guardado válido SHALL crear una versión nueva con número consecutivo y fecha, y convertirla en la activa. Las versiones anteriores SHALL conservarse sin modificarse. El sistema SHALL validar: nombre del tema de 1 a 60 caracteres, descripción de hasta 500, entre 1 y 20 subtemas de 1 a 60 caracteres cada uno, y negativa de 1 a 300 caracteres.

#### Scenario: Cambio de tema
- **WHEN** el admin guarda la configuración con el tema "cafeteras" y sus subtemas
- **THEN** se crea la versión siguiente, pasa a ser la activa, y la versión anterior sigue disponible en el historial

#### Scenario: Configuración inválida
- **WHEN** el admin intenta guardar con el nombre del tema vacío, sin subtemas o con una negativa de más de 300 caracteres
- **THEN** el sistema rechaza el guardado con un error de validación, no crea ninguna versión y la configuración activa no cambia

#### Scenario: Guardados concurrentes
- **WHEN** dos admins guardan configuraciones distintas casi al mismo tiempo
- **THEN** ambas quedan como versiones consecutivas distintas y la activa es la última en confirmarse

### Requirement: Historial de versiones
El sistema SHALL permitir consultar el historial de versiones del alcance, de la más reciente a la más antigua, con todos sus campos y su fecha.

#### Scenario: Consulta del historial
- **WHEN** existen las versiones 1 (bicicletas) y 2 (cafeteras)
- **THEN** el historial devuelve la 2 y luego la 1, cada una con su tema, subtemas, negativa y fecha

### Requirement: El cambio aplica desde la siguiente respuesta del bot
Una configuración nueva SHALL aplicarse a todas las respuestas del bot que empiecen a generarse después de guardarla, en todas las conversaciones, sin reiniciar el servidor. Las respuestas ya publicadas no SHALL modificarse, y una respuesta que ya se estaba generando al guardar SHALL completarse con la versión con la que empezó.

#### Scenario: Pregunta después del cambio
- **WHEN** el admin cambia el tema de "bicicletas" a "cafeteras" y luego un cliente pregunta "¿Cada cuánto lubrico la cadena de mi bici?"
- **THEN** el bot responde con la negativa configurada para "cafeteras"

#### Scenario: Respuestas anteriores intactas
- **WHEN** el tema cambia después de que el bot respondiera sobre bicicletas en una conversación
- **THEN** esas respuestas siguen igual en el historial de la conversación
