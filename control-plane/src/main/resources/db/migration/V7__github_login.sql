-- GitHub joins Google as a login method: its own id column, same rules as google_id.
ALTER TABLE users ADD COLUMN github_id TEXT UNIQUE;

-- The "can log in somehow" rule now has three ways to be true.
ALTER TABLE users DROP CONSTRAINT users_has_login_method;
ALTER TABLE users ADD CONSTRAINT users_has_login_method
    CHECK (password_hash IS NOT NULL OR google_id IS NOT NULL OR github_id IS NOT NULL);
