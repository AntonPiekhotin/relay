-- Gives a direct dialog an identity derived from who is in it.
--
-- Until now the only way to create a dialog was `POST /internal/api/v1/dialogs`, which took a
-- participant list at face value, so nothing stopped two rows describing the same pair of people.
-- Opening a chat is about to become a client action, and "open the chat with Bob" has to resolve to
-- the same dialog every time — including when both people tap it at the same moment. That is a
-- uniqueness problem, and it is settled here rather than by a SELECT-before-INSERT that races, for
-- the same reason messages dedup on a constraint and `active_calls` decides busy with its primary
-- key (CLAUDE.md invariants 6 and 11).

ALTER TABLE dialogs ADD COLUMN type varchar(16);

-- Existing rows predate the distinction, so it is inferred from membership. Two participants is a
-- direct dialog; anything else is a group. Nothing else was ever recorded to go on.
UPDATE dialogs d
SET type = CASE
               WHEN (SELECT count(*) FROM dialog_participants p WHERE p.dialog_id = d.id) = 2
                   THEN 'DIRECT'
               ELSE 'GROUP'
    END;

ALTER TABLE dialogs ALTER COLUMN type SET NOT NULL;

-- The two participant ids sorted and joined, so the key does not depend on who opened the chat.
-- Width is 64 + 1 + 64, matching `dialog_participants.user_id`.
--
-- NULL for a group: a group is not addressable by its membership — the same three people can want
-- two separate groups — and Postgres lets a unique constraint hold any number of NULLs, so group
-- rows simply sit outside it.
ALTER TABLE dialogs ADD COLUMN direct_key varchar(129);

UPDATE dialogs d
SET direct_key = (SELECT string_agg(p.user_id, ':' ORDER BY p.user_id)
                  FROM dialog_participants p
                  WHERE p.dialog_id = d.id)
WHERE d.type = 'DIRECT';

-- If the old data already holds two dialogs for one pair, the oldest keeps the key and the others
-- give it up. Deleting them would take their messages with them, and leaving them all keyed would
-- fail the constraint below. Unkeyed, they keep their history and merely stop being what "open the
-- chat with Bob" resolves to.
--
-- The subquery reads the pre-update snapshot of the same statement, so every loser is cleared in
-- one pass rather than each one clearing the next.
UPDATE dialogs d
SET direct_key = NULL
WHERE d.direct_key IS NOT NULL
  AND EXISTS (SELECT 1
              FROM dialogs older
              WHERE older.direct_key = d.direct_key
                AND (older.created_at, older.id) < (d.created_at, d.id));

ALTER TABLE dialogs
    ADD CONSTRAINT uk_dialogs_direct_key UNIQUE (direct_key);
