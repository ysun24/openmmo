-- r5-frontier: the Battle Frontier part of SaveBlock2 (frontier.*) per character: Battle Points, the
-- seven facilities' streaks per battle mode and level mode, the Silver/Gold symbols, the Factory
-- rental count, the Battle Tents, and the Trainer Hill / FRLG Trainer Tower best times.
-- data is a JSON document, see FrontierSave.toJson in server.game frontier/FrontierRecords.kt.
CREATE TABLE frontier_records (
  character_id  BIGINT    NOT NULL PRIMARY KEY REFERENCES characters (id) ON DELETE CASCADE,
  battle_points INTEGER   NOT NULL DEFAULT 0,
  data          TEXT      NOT NULL DEFAULT '{}',
  updated_at    TIMESTAMP NOT NULL
);
