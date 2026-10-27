-- How password_hash is stored. 'sha1' is the legacy raw SHA-1 hex the client sends; such rows are
-- rewritten as 'pbkdf2-sha256' on their next successful login. A pbkdf2 hash is
-- "<iterations>$<base64 salt>$<base64 key>" over the client's SHA-1 hex.
ALTER TABLE users
  ADD COLUMN password_scheme VARCHAR(16) NOT NULL DEFAULT 'sha1';

ALTER TABLE users
  ALTER COLUMN password_hash TYPE VARCHAR(128);

-- Invite codes for self registration. A code works while uses < max_uses and it has not expired.
CREATE TABLE invites (
  code        VARCHAR(32) NOT NULL PRIMARY KEY,
  created_by  VARCHAR(32) NOT NULL,
  max_uses    INT         NOT NULL DEFAULT 1,
  uses        INT         NOT NULL DEFAULT 0,
  expires_at  TIMESTAMP,
  created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
