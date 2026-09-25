-- Idempotent schema, applied on every start (spring.sql.init.mode=always).

-- One row per version of the bot scope; the active one is max(version). Rows are never updated.
CREATE TABLE IF NOT EXISTS bot_scope (
    version         INT           PRIMARY KEY,
    topic           VARCHAR(60)   NOT NULL,
    description     VARCHAR(500)  NOT NULL DEFAULT '',
    subtopics       TEXT[]        NOT NULL,
    refusal_message VARCHAR(300)  NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

INSERT INTO bot_scope (version, topic, description, subtopics, refusal_message)
VALUES (1,
        'bicicletas',
        'Soporte y consejos sobre bicicletas y ciclismo.',
        ARRAY['mecánica y mantenimiento', 'repuestos y componentes', 'tallas y ajuste',
              'tipos de bicicleta', 'accesorios y equipo', 'rutas y técnica', 'seguridad al rodar'],
        'Solo puedo ayudarte con temas de bicicletas. Tu pregunta está fuera de mi alcance.')
ON CONFLICT (version) DO NOTHING;

-- last_seq is the per-conversation sequence counter: incremented under the row lock in the
-- same transaction that inserts the message, so seqs are dense (1, 2, 3...) and never repeat.
CREATE TABLE IF NOT EXISTS conversation (
    id               UUID         PRIMARY KEY,
    customer_name    VARCHAR(60)  NOT NULL,
    last_seq         BIGINT       NOT NULL DEFAULT 0,
    last_sender_role VARCHAR(10),
    last_preview     VARCHAR(120),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_activity_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_conversation_last_activity ON conversation (last_activity_at DESC);

CREATE TABLE IF NOT EXISTS message (
    id                BIGSERIAL     PRIMARY KEY,
    conversation_id   UUID          NOT NULL REFERENCES conversation (id),
    seq               BIGINT        NOT NULL,
    client_message_id UUID          NOT NULL,
    sender_role       VARCHAR(10)   NOT NULL CHECK (sender_role IN ('CUSTOMER', 'BOT', 'AGENT', 'SYSTEM')),
    sender_name       VARCHAR(60)   NOT NULL,
    content           VARCHAR(2000) NOT NULL,
    reply_to_seq      BIGINT,
    bot_scope_version INT           REFERENCES bot_scope (version),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_message_seq UNIQUE (conversation_id, seq),
    CONSTRAINT uq_message_client_id UNIQUE (conversation_id, client_message_id)
);

CREATE INDEX IF NOT EXISTS idx_message_reply_to ON message (conversation_id, reply_to_seq);

-- Which LLM produced each bot reply (shown in the admin). ALTER so existing databases get the columns too.
ALTER TABLE message ADD COLUMN IF NOT EXISTS bot_provider VARCHAR(20);
ALTER TABLE message ADD COLUMN IF NOT EXISTS bot_model VARCHAR(80);

-- Human takeover (design D15): who attends the conversation. BOT = the assistant, HUMAN = agent_name.
-- The bot only answers customer messages with seq > bot_resume_after_seq (set when an agent hands it back).
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS mode VARCHAR(10) NOT NULL DEFAULT 'BOT';
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS agent_name VARCHAR(60);
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS bot_resume_after_seq BIGINT NOT NULL DEFAULT 0;

-- Existing databases have the old check without AGENT: replace it (idempotent on every start).
ALTER TABLE message DROP CONSTRAINT IF EXISTS message_sender_role_check;
ALTER TABLE message ADD CONSTRAINT message_sender_role_check
    CHECK (sender_role IN ('CUSTOMER', 'BOT', 'AGENT', 'SYSTEM'));
