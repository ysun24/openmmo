-- r3-items: items and progression outside battle.
--
-- status: the persisted Gen 3 status1 column is added once, by V8_1 (INT NOT NULL DEFAULT 0).

-- PP Up / PP Max: two bits per move slot (slot 0 in the low bits), as ppBonuses in the decomp.
ALTER TABLE pokemon ADD COLUMN IF NOT EXISTS pp_bonuses SMALLINT NOT NULL DEFAULT 0;

-- Pokedex: a row means the species was seen; caught = TRUE once it was owned.
CREATE TABLE IF NOT EXISTS character_pokedex (
  character_id BIGINT   NOT NULL REFERENCES characters (id) ON DELETE CASCADE,
  dex_id       SMALLINT NOT NULL,
  caught       BOOLEAN  NOT NULL DEFAULT FALSE,
  PRIMARY KEY (character_id, dex_id)
);
