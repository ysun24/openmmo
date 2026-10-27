-- r3-items: items and progression outside battle.
--
-- status: the Gen 3 non-volatile status (status1 bits of the decomp: sleep turns in bits 0-2,
-- poison 1<<3, burn 1<<4, freeze 1<<5, paralysis 1<<6, bad poison 1<<7). NULL or 0 is healthy.
-- The battle branch adds the same column in V8_1; IF NOT EXISTS keeps both orders working.
ALTER TABLE pokemon ADD COLUMN IF NOT EXISTS status SMALLINT;

-- PP Up / PP Max: two bits per move slot (slot 0 in the low bits), as ppBonuses in the decomp.
ALTER TABLE pokemon ADD COLUMN IF NOT EXISTS pp_bonuses SMALLINT NOT NULL DEFAULT 0;

-- Pokedex: a row means the species was seen; caught = TRUE once it was owned.
CREATE TABLE IF NOT EXISTS character_pokedex (
  character_id BIGINT   NOT NULL REFERENCES characters (id) ON DELETE CASCADE,
  dex_id       SMALLINT NOT NULL,
  caught       BOOLEAN  NOT NULL DEFAULT FALSE,
  PRIMARY KEY (character_id, dex_id)
);
