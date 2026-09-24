-- One row per person. A person can log in with a password, with Google, or with
-- both: the two methods are optional columns rather than a 'provider' field, so
-- signing in with Google on an email that already has a password reaches the
-- same row instead of failing on the unique email.
-- email is stored lowercased by the application; UNIQUE here compares exact strings.
CREATE TABLE users (
                       id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                       email          TEXT NOT NULL UNIQUE,
                       password_hash  TEXT,
                       google_id      TEXT UNIQUE,
                       created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                       last_login_at  TIMESTAMPTZ,
    -- An account with neither can never log in; refuse it here rather than trust every insert path.
                       CONSTRAINT users_has_login_method
                           CHECK (password_hash IS NOT NULL OR google_id IS NOT NULL)
);

-- Successful logins only. Recording failures would need a nullable user_id
-- (a wrong email matches no user) -- deliberately not built yet.
CREATE TABLE login_events (
                              id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                              user_id     UUID NOT NULL REFERENCES users(id),
                              method      VARCHAR(20) NOT NULL,
                              ip          TEXT,
                              user_agent  TEXT,
                              at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_login_events_user_id ON login_events(user_id);

-- Who asked: a users.id, or 'guest:<uuid>' for a guest token. NULL for runs
-- created before auth existed. Indexed because the guest cap counts by it on every submit.
ALTER TABLE runs ADD COLUMN owner_sub TEXT;
CREATE INDEX idx_runs_owner_sub ON runs(owner_sub);