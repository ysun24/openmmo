-- r4-gaps: Emerald secret bases. One base per character (SaveBlock1 secretBases[0]); friends see it
-- at the same spot. secret_base_id is the decomp's SECRET_BASE_* id (map = id / 10).
CREATE TABLE secret_bases (
  character_id   BIGINT       NOT NULL PRIMARY KEY REFERENCES characters (id) ON DELETE CASCADE,
  secret_base_id SMALLINT     NOT NULL,
  -- Up to 16 placed decorations (DECOR_MAX_SECRET_BASE): "decorId:x:y" joined by ",", where x is
  -- the decoration's left column and y its bottom row on the base map.
  decorations    VARCHAR(256) NOT NULL DEFAULT '',
  times_entered  SMALLINT     NOT NULL DEFAULT 0,
  created_at     TIMESTAMP    NOT NULL
);
CREATE INDEX idx_secret_bases_id ON secret_bases (secret_base_id);
