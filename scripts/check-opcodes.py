#!/usr/bin/env python3
"""Lists every opcode registration in GameProtocol.kt and fails on a clash.

A clash is two registrations of one opcode in one direction (c2s, s2c; bidi counts for both) or one
packet type registered twice in a direction. Run from the repository root:

    python3 scripts/check-opcodes.py [--table]

--table prints the full opcode table per direction. Exit status 1 on a clash.
"""
import re
import sys
from collections import defaultdict
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent / (
    "protocols.game/src/main/kotlin/de/fiereu/openmmo/net/game/GameProtocol.kt")

# c2s<Type>(0xABu, Codec) / s2c<...> / bidi<...>, possibly spread over lines, type maybe qualified.
REG = re.compile(r"\b(c2s|s2c|bidi)<\s*([\w.]+)\s*>\s*\(\s*(0x[0-9A-Fa-f]+)u", re.S)


def main() -> int:
    text = SRC.read_text()
    by_op = defaultdict(list)
    by_type = defaultdict(list)
    for kind, typ, op in REG.findall(text):
        name = typ.rsplit(".", 1)[-1]
        dirs = ["c2s", "s2c"] if kind == "bidi" else [kind]
        for d in dirs:
            by_op[(d, int(op, 16))].append(name)
            by_type[(d, name)].append(int(op, 16))
    clashes = [(k, v) for k, v in by_op.items() if len(v) > 1]
    dup_types = [(k, v) for k, v in by_type.items() if len(v) > 1]
    if "--table" in sys.argv:
        for d in ("c2s", "s2c"):
            print(f"== {d}")
            for (dd, op), names in sorted(by_op.items()):
                if dd == d:
                    print(f"  0x{op:02X} {', '.join(names)}")
    total = sum(len(v) for v in by_op.values())
    print(f"{total} registrations, {len(clashes)} opcode clashes, {len(dup_types)} duplicate types")
    for (d, op), names in clashes:
        print(f"CLASH {d} 0x{op:02X}: {names}")
    for (d, name), ops in dup_types:
        print(f"DUPLICATE {d} {name}: {[hex(o) for o in ops]}")
    return 1 if clashes or dup_types else 0


if __name__ == "__main__":
    sys.exit(main())
