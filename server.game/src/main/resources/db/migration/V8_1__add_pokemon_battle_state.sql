-- r3-battle: battle state a monster carries out of a battle.
--   status      Gen 3 packed status1 (sleep turns in bits 0-2, then poison 0x08, burn 0x10,
--               freeze 0x20, paralysis 0x40, bad poison 0x80); 0 = healthy. Poké Centers clear it.
--   friendship  0-255, friendship evolutions need 220. Gen 3 starts a caught monster at its
--               species' base friendship; 70 is the common value and the default for old rows.
--   held_item   the held item's wire id, 0 = none.
ALTER TABLE pokemon ADD COLUMN status     INT      NOT NULL DEFAULT 0;
ALTER TABLE pokemon ADD COLUMN friendship SMALLINT NOT NULL DEFAULT 70;
ALTER TABLE pokemon ADD COLUMN held_item  INT      NOT NULL DEFAULT 0;
