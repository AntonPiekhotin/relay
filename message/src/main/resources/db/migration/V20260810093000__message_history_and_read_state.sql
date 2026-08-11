-- Everything the history API needs to exist: an index the keyset pages can actually ride, a
-- dialog list that can be ordered by activity, and somewhere to keep per-user read position.
--
-- Until now `messages` had exactly one query against it — the idempotency lookup — so none of this
-- was load-bearing. It is now: a client that reinstalls, clears its data, or switches device has no
-- copy of its own history, and a frame that arrived while the socket was down was unrecoverable
-- (`docs/PROTOCOL.md` §7). Read state is the same gap one level up: the client has an unread count
-- with nothing to populate it.

-- "Which dialogs is this user in" is the driving filter of the dialog list, and it had no index.
-- `pk_dialog_participants` is (dialog_id, user_id), so a lookup by user alone could only be a
-- sequential scan over every membership row in the system.
CREATE INDEX ix_dialog_participants_user ON dialog_participants (user_id);

-- Denormalized rather than a MAX(sent_at) subquery per dialog. The dialog list is the app's home
-- screen — it is fetched on every cold start, it is ordered by this column, and a lateral join
-- against `messages` degrades as history grows while this does not. The write cost is one UPDATE
-- inside the send transaction that was already writing.
--
-- Nullable on purpose: a dialog that was opened and never used has no last message, and that is
-- different from one whose last message is at the epoch. Those sort last.
ALTER TABLE dialogs ADD COLUMN last_message_at timestamp(6) with time zone;

UPDATE dialogs d
SET last_message_at = (SELECT max(m.sent_at) FROM messages m WHERE m.dialog_id = d.id);

-- `id` as the tiebreaker for the same reason the message index carries one: two dialogs can have
-- their last message in the same millisecond, and the list has to be deterministic.
CREATE INDEX ix_dialogs_last_message_at ON dialogs (last_message_at DESC, id DESC);

-- The keyset pagination in `docs/DATA.md` §7 compares `(sent_at, id)` as a row value. The old
-- index was (dialog_id, sent_at) ascending with no tiebreaker, which left the `id` half of every
-- cursor comparison as a filter applied after the scan rather than a bound on it. Postgres can read
-- an ascending index backwards, so the direction was never the problem — the missing column was.
--
-- The old index is dropped rather than kept: (dialog_id, sent_at DESC, id DESC) serves every query
-- the old one did, in either direction.
DROP INDEX ix_messages_dialog_sent_at;
CREATE INDEX ix_messages_dialog_sent_at_id ON messages (dialog_id, sent_at DESC, id DESC);

-- One row per (dialog, user) holding a *position*, not a per-message flag.
--
-- A cursor is what makes marking read cheap and idempotent: opening a chat with 500 unread messages
-- is one upsert rather than a 500-row UPDATE, a repeated command is a no-op, and a command that
-- arrives out of order cannot move the cursor backwards (see the ON CONFLICT guard in
-- `ReadStateRepository`). It also generalises to group dialogs with no schema change, which a
-- `messages.read_at` column would not.
--
-- Both halves of the position are stored because the unread count compares them as a row value
-- against `(sent_at, id)`, exactly like the history cursors: `last_read_at` alone cannot separate
-- two messages sent in the same millisecond.
CREATE TABLE dialog_read_state (
    dialog_id    uuid                        NOT NULL,
    user_id      varchar(64)                 NOT NULL,
    last_read_at timestamp(6) with time zone NOT NULL,
    last_read_id uuid                        NOT NULL,
    updated_at   timestamp(6) with time zone NOT NULL,
    CONSTRAINT pk_dialog_read_state PRIMARY KEY (dialog_id, user_id),
    CONSTRAINT fk_dialog_read_state_dialog FOREIGN KEY (dialog_id) REFERENCES dialogs (id)
);

-- No foreign key on `last_read_id`: `messages` itself carries no key to `dialogs`, and the cursor
-- is a position rather than a reference to a row anything reads. The service checks that the message
-- belongs to the dialog before storing it, which is the part that matters.
