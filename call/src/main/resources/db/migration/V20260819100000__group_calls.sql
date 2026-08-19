-- Group calls. Additive only: every direct-call row and code path is untouched.

-- 'direct' | 'group'. A column rather than "infer from the participant count", because a group
-- call with a single invitee has two participants exactly like a direct call — and the two kinds
-- run different state machines (accept/reject vs join/decline/leave), so which machine owns the
-- row is a fact worth storing, not deriving.
ALTER TABLE calls
    ADD COLUMN kind varchar(8) NOT NULL DEFAULT 'direct';

-- Per-participant lifecycle: invited | joined | declined | missed | left. A direct call never
-- branches on it (its two-party state lives on the call row), but it is stamped there too so the
-- column means the same thing on every row.
ALTER TABLE call_participants
    ADD COLUMN state varchar(16) NOT NULL DEFAULT 'invited';

-- Best-effort backfill for existing history: anyone with a media session recorded had joined.
UPDATE call_participants
SET state = 'joined'
WHERE joined_at IS NOT NULL;
