-- Baseline for the message service schema.
--
-- Captures what `ddl-auto: update` had accumulated up to this point, so an existing database and a
-- fresh one end up identical. From here the schema moves only through migrations, and
-- `ddl-auto: validate` holds the entities to it.
--
-- Constraint names are spelled out. Hibernate generated the participants foreign key as
-- `FKewktqtk2mxvw9m1i8uosk78gk`; a later migration that has to drop it would be guessing.

-- A conversation. Membership hangs off it rather than living on the message, because the fan-out
-- list has to be resolved server-side (ARCHITECTURE.md §19.2) — letting a client name its own
-- recipients would let it push to anyone.
CREATE TABLE dialogs (
    id         uuid                     NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT pk_dialogs PRIMARY KEY (id)
);

-- Membership, as an element collection on `Dialog`. The primary key over both columns is what
-- makes a participant list a set: the same user cannot be added to a dialog twice.
CREATE TABLE dialog_participants (
    dialog_id uuid        NOT NULL,
    user_id   varchar(64) NOT NULL,
    CONSTRAINT pk_dialog_participants PRIMARY KEY (dialog_id, user_id),
    CONSTRAINT fk_dialog_participants_dialog FOREIGN KEY (dialog_id) REFERENCES dialogs (id)
);

-- The unique constraint on (sender_id, client_message_id) is what makes sending idempotent
-- (§19.2). The client owns its UUID space, so a retry — over the socket or via the REST fallback
-- after a lost ack — cannot produce a second row. It lives in the schema rather than only in code
-- because two concurrent retries would both pass an application-level check.
--
-- `dialog_id` deliberately carries no foreign key to `dialogs`: the entity maps it as a bare UUID,
-- and adding one now would be a behaviour change rather than a baseline. Worth revisiting.
CREATE TABLE messages (
    id                uuid                     NOT NULL,
    dialog_id         uuid                     NOT NULL,
    sender_id         varchar(64)              NOT NULL,
    text              varchar(4000)            NOT NULL,
    client_message_id varchar(64)              NOT NULL,
    sent_at           timestamp(6) with time zone NOT NULL,
    CONSTRAINT pk_messages PRIMARY KEY (id),
    CONSTRAINT uk_messages_sender_client_id UNIQUE (sender_id, client_message_id)
);

-- History is read one dialog at a time, newest last. Column order matters: `dialog_id` first so
-- the equality narrows, `sent_at` second so the range and the ordering both come off the index.
CREATE INDEX ix_messages_dialog_sent_at ON messages (dialog_id, sent_at);