#!/usr/bin/env python3
"""r5-scripts: drop the generated `TODO("port X")` script stubs from server.game.

Every label without a hand port now runs on the decomp script interpreter
(ScriptRegistry falls back to it), so a stub would only shadow the real script.
Removes each stub object with its "Not ported yet" KDoc and its registry line, deletes
map files left without scripts and their GeneratedScripts entries. Idempotent.
"""
import os, re, sys

root = sys.argv[1] if len(sys.argv) > 1 else 'server.game/src/main/kotlin/de/fiereu/openmmo/server/game/script/generated'
stub = re.compile(
    r'(?:/\*\*\n(?: \*[^\n]*\n)*? \*/\n)?internal object (\w+) : Script \{\n'
    r'  override suspend fun run\(ctx: ScriptContext\) =\s*TODO\("port \w+"\)\n\}\n\n?')
removed_total = 0
deleted = []
for region in ('kanto', 'hoenn'):
    d = os.path.join(root, region)
    for f in sorted(os.listdir(d)):
        p = os.path.join(d, f)
        s = open(p).read()
        names = []
        def drop(m):
            names.append(m.group(1))
            return ''
        s2 = stub.sub(drop, s)
        if not names:
            continue
        for n in names:
            s2 = re.sub(r'\n\s*"%s" to\s+%s,' % (n, n), '', s2)
        removed_total += len(names)
        mapname = f[:-3]
        if not re.search(r'" to\s+\w+,', s2) and 'internal object' not in s2:
            os.remove(p)
            deleted.append('de.fiereu.openmmo.server.game.script.generated.%s.%sScripts' % (region, mapname))
        else:
            open(p, 'w').write(s2)
reg = os.path.join(root, 'GeneratedScripts.kt')
s = open(reg).read()
for full in deleted:
    s = re.sub(r'\s*putAll\(\s*%s\s*\)' % re.escape(full), '', s)
open(reg, 'w').write(s)
print('removed %d stubs, deleted %d files' % (removed_total, len(deleted)))
