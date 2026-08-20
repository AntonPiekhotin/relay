-- Group dialogs: a title, a single owner-admin, and membership-change system messages.
-- Additive only; every direct-dialog row and code path is untouched (docs/DATA.md §8).

-- NULL for DIRECT — a direct dialog is named by its members, never stored. Required
-- app-side for every group created through the client-facing endpoint.
ALTER TABLE dialogs ADD COLUMN title varchar(128);

-- The single admin (CLAUDE.md-level decision: creator is admin, no transfer in v1).
-- NULL for DIRECT, and NULL for legacy /internal-created groups, which predate ownership:
-- an admin-less group answers 403 to every owner-only mutation rather than inventing an
-- owner in a migration.
ALTER TABLE dialogs ADD COLUMN owner_id varchar(64);

-- Legacy groups get a rendering fallback so title can be required app-side for new ones.
UPDATE dialogs SET title = 'Group' WHERE type = 'GROUP' AND title IS NULL;

-- 'USER' | 'GROUP_CREATED' | 'MEMBER_ADDED' | 'MEMBER_REMOVED' | 'MEMBER_LEFT' | 'GROUP_RENAMED'.
-- System messages are rows in the same table on purpose: they order, page, and count with the
-- (dialog_id, sent_at, id) index like any message, so history needs no second query and the
-- unread count needs no change.
ALTER TABLE messages ADD COLUMN kind varchar(32) NOT NULL DEFAULT 'USER';

-- The member a membership system message is about. NULL for USER, GROUP_CREATED and
-- GROUP_RENAMED; equal to sender_id for MEMBER_LEFT. Structured rather than rendered into
-- text — clients resolve names through user-service, this service holds ids only.
ALTER TABLE messages ADD COLUMN target_user_id varchar(64);
