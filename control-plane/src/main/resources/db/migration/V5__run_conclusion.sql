-- The reader-facing answer, written after the Critic has graded every claim:
-- {"answer": ..., "answerClaimIds": [...], "takeaways": [{"text": ..., "claimIds": [...]}]}.
-- Built only from claims that passed, and every sentence points back at them, so
-- the conclusion adds no fact the Critic didn't check. NULL for runs finished
-- before this existed, or when writing it failed -- the claims still render.
ALTER TABLE runs ADD COLUMN conclusion JSONB;
