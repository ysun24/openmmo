#!/usr/bin/env python3
"""r5-scripts: rebuild server.game's GeneratedScripts.kt from the per-map script files that exist."""
import os, re, sys
root = sys.argv[1] if len(sys.argv) > 1 else 'server.game/src/main/kotlin/de/fiereu/openmmo/server/game/script/generated'
reg = open(os.path.join(root, 'GeneratedScripts.kt')).read()
flat = re.sub(r'\s+', '', reg)
order = re.findall(r'putAll\((de\.fiereu\.openmmo\.server\.game\.script\.generated\.\w+\.\w+)\)', flat)
exist = set()
for region in ('kanto', 'hoenn'):
    for f in os.listdir(os.path.join(root, region)):
        s = open(os.path.join(root, region, f)).read()
        for m in re.finditer(r'internal val (\w+Scripts): Map<String, Script>', s):
            exist.add('de.fiereu.openmmo.server.game.script.generated.%s.%s' % (region, m.group(1)))
keep = [v for v in order if v in exist] + sorted(exist - set(order))
chunks = [keep[i:i + 40] for i in range(0, len(keep), 40)]
head = reg[:reg.index('internal object GeneratedScripts')]
out = [head, 'internal object GeneratedScripts {\n  val byLabel: Map<String, Script> =\n      HashMap<String, Script>().apply {\n']
out += ['        chunk%d()\n' % i for i in range(len(chunks))]
out.append('      }\n\n')
for i, c in enumerate(chunks):
    out.append('  private fun MutableMap<String, Script>.chunk%d() {\n' % i)
    out += ['    putAll(%s)\n' % v for v in c]
    out.append('  }\n\n')
out.append('}\n')
open(os.path.join(root, 'GeneratedScripts.kt'), 'w').write(''.join(out))
print('registry: %d map tables' % len(keep))
