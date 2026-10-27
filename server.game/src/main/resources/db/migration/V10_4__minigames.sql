-- r5-minigames: Game Corner coins, the minigame / Fame Checker / Easy Chat save data, Mystery
-- Gift and record mixing.

-- One row per character: the COIN CASE (MAX_COINS 9999) and the rest of this lane's save data as
-- a JSON object (Fame Checker pick states and flavor text flags, Easy Chat profile, minigame
-- records, event tickets, TV shows already watched).
CREATE TABLE minigame_players (
  character_id BIGINT    NOT NULL PRIMARY KEY REFERENCES characters (id) ON DELETE CASCADE,
  coins        INTEGER   NOT NULL DEFAULT 0 CHECK (coins BETWEEN 0 AND 9999),
  data         TEXT      NOT NULL DEFAULT '{}',
  updated_at   TIMESTAMP NOT NULL
);

-- An admin-defined Mystery Gift (a WONDER CARD): kind item | mon | ticket, payload a JSON object
-- ({item, qty} / {species, level, moves} / {ticket}). region 0 = both games, 1 = Kanto, 2 = Hoenn
-- (the wire region id + 1).
CREATE TABLE mystery_gifts (
  id         SERIAL       PRIMARY KEY,
  kind       VARCHAR(16)  NOT NULL,
  region     SMALLINT     NOT NULL DEFAULT 0,
  title      VARCHAR(64)  NOT NULL,
  body       VARCHAR(256) NOT NULL DEFAULT '',
  payload    TEXT         NOT NULL DEFAULT '{}',
  active     BOOLEAN      NOT NULL DEFAULT TRUE,
  created_by VARCHAR(32)  NOT NULL DEFAULT '',
  created_at TIMESTAMP    NOT NULL
);

-- Which character took which card (state 0 = card received, 1 = delivered by the deliveryman).
CREATE TABLE mystery_gift_claims (
  gift_id      INTEGER   NOT NULL REFERENCES mystery_gifts (id) ON DELETE CASCADE,
  character_id BIGINT    NOT NULL REFERENCES characters (id) ON DELETE CASCADE,
  state        SMALLINT  NOT NULL DEFAULT 0,
  claimed_at   TIMESTAMP NOT NULL,
  PRIMARY KEY (gift_id, character_id)
);

-- Record mixing: what a player shares with everyone else (a TV show, a secret base listing, a
-- Lilycove trainer, a Dewford trendy phrase), kind + JSON payload.
CREATE TABLE mixed_records (
  id           BIGSERIAL   PRIMARY KEY,
  character_id BIGINT      NOT NULL REFERENCES characters (id) ON DELETE CASCADE,
  kind         VARCHAR(16) NOT NULL,
  payload      TEXT        NOT NULL,
  created_at   TIMESTAMP   NOT NULL
);
CREATE INDEX idx_mixed_records_kind ON mixed_records (kind, created_at DESC);
