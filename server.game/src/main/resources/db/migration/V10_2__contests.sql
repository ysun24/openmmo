-- r5-contests: contest stats and ribbons per monster, the Pokeblock case, berry trees and the
-- Lilycove museum paintings.

-- MON_DATA_COOL..MON_DATA_SHEEN and the ribbons of one monster. Keyed by the monster id, which a
-- trade keeps, so the stats travel with the monster. No foreign key: the monster row is written by
-- the character flush, which may come after a Pokeblock was fed.
CREATE TABLE pokemon_contest (
  pokemon_id    BIGINT   NOT NULL PRIMARY KEY,
  cool          SMALLINT NOT NULL DEFAULT 0,
  beauty        SMALLINT NOT NULL DEFAULT 0,
  cute          SMALLINT NOT NULL DEFAULT 0,
  smart         SMALLINT NOT NULL DEFAULT 0,
  tough         SMALLINT NOT NULL DEFAULT 0,
  sheen         SMALLINT NOT NULL DEFAULT 0,
  -- Highest rank ribbon per category: 0 none, 1 Normal .. 4 Master (MON_DATA_COOL_RIBBON..).
  cool_ribbon   SMALLINT NOT NULL DEFAULT 0,
  beauty_ribbon SMALLINT NOT NULL DEFAULT 0,
  cute_ribbon   SMALLINT NOT NULL DEFAULT 0,
  smart_ribbon  SMALLINT NOT NULL DEFAULT 0,
  tough_ribbon  SMALLINT NOT NULL DEFAULT 0,
  -- Other ribbons as bits: 1 champion, 2 winning, 4 victory, 8 artist, 16 effort.
  ribbons       INTEGER  NOT NULL DEFAULT 0
);

-- The POKEBLOCK CASE (SaveBlock1 pokeblocks[40]). color is PBLOCK_CLR_* (1..14).
CREATE TABLE pokeblocks (
  character_id BIGINT   NOT NULL REFERENCES characters (id) ON DELETE CASCADE,
  slot         SMALLINT NOT NULL,
  color        SMALLINT NOT NULL,
  spicy        SMALLINT NOT NULL,
  dry          SMALLINT NOT NULL,
  sweet        SMALLINT NOT NULL,
  bitter       SMALLINT NOT NULL,
  sour         SMALLINT NOT NULL,
  feel         SMALLINT NOT NULL,
  PRIMARY KEY (character_id, slot)
);

-- SaveBlock1 berryTrees[BERRY_TREES_COUNT], per player. tree_id is the decomp BERRY_TREE_* id,
-- updated_at is when minutes_until_next_stage was last brought up to date.
CREATE TABLE berry_trees (
  character_id             BIGINT    NOT NULL REFERENCES characters (id) ON DELETE CASCADE,
  tree_id                  SMALLINT  NOT NULL,
  berry                    SMALLINT  NOT NULL,
  stage                    SMALLINT  NOT NULL,
  minutes_until_next_stage INTEGER   NOT NULL,
  berry_yield              SMALLINT  NOT NULL,
  regrowth_count           SMALLINT  NOT NULL,
  -- watered1..watered4 as bits 0-3
  watered                  SMALLINT  NOT NULL,
  stop_growth              BOOLEAN   NOT NULL,
  updated_at               TIMESTAMP NOT NULL,
  PRIMARY KEY (character_id, tree_id)
);

-- Per character contest bookkeeping: the new-game trees were planted, the blender record.
CREATE TABLE character_contest (
  character_id      BIGINT  NOT NULL PRIMARY KEY REFERENCES characters (id) ON DELETE CASCADE,
  trees_initialized BOOLEAN NOT NULL DEFAULT FALSE,
  -- Best blender RPM in hundredths (berryBlenderRecords).
  blender_record    INTEGER NOT NULL DEFAULT 0
);

-- The Lilycove museum's paintings (CONTEST_WINNER_MUSEUM_*), one per category per player.
CREATE TABLE contest_paintings (
  character_id BIGINT      NOT NULL REFERENCES characters (id) ON DELETE CASCADE,
  category     SMALLINT    NOT NULL,
  pokemon_id   BIGINT      NOT NULL,
  dex          SMALLINT    NOT NULL,
  shiny        BOOLEAN     NOT NULL,
  nickname     VARCHAR(16) NOT NULL,
  trainer      VARCHAR(16) NOT NULL,
  contest_rank SMALLINT    NOT NULL,
  created_at   TIMESTAMP   NOT NULL,
  PRIMARY KEY (character_id, category)
);
