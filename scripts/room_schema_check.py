#!/usr/bin/env python3
"""Check DatabaseMigrations' hand-written scraping_configs schema (v4 CREATE TABLE plus the
v5 ALTER TABLE ADD COLUMN) against Room's exported schema for the current version.

Room validates a migrated table column-by-column (affinity, NOT NULL, primary key)
at first open and crashes the app on mismatch, so this guards the upgrade path in CI.
"""
import glob, json, re, sys

def columns(sql):
    body = sql[sql.index('(') + 1: sql.rindex(')')]
    cols = {}
    for part in re.split(r',\s*(?=`|PRIMARY)', body):
        part = part.strip()
        m = re.match(r'`(\w+)`\s+(\w+)(.*)', part)
        if m:
            cols[m.group(1)] = (m.group(2).upper(), 'NOT NULL' in m.group(3).upper())
    pk = re.search(r'PRIMARY KEY\(([^)]*)\)', body)
    return cols, (pk.group(1).replace('`', '').replace(' ', '') if pk else None)

schema_files = sorted(glob.glob('app/schemas/**/5.json', recursive=True))
if not schema_files:
    print('Room schema 5.json not found'); sys.exit(1)
schema = json.load(open(schema_files[0]))
entities = {e['tableName']: e for e in schema['database']['entities']}
room_sql = entities['scraping_configs']['createSql'].replace('${TABLE_NAME}', 'scraping_configs')
print('Room createSql (scraping_configs):', room_sql)
src = open('app/src/main/java/com/aggregatorx/app/data/database/DatabaseMigrations.kt').read()
m = re.search(r'CREATE_SCRAPING_CONFIGS_V4 = (.*?)\n\n', src, re.S)
mig_sql = ''.join(re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1)))
print('Migration createSql:', mig_sql)
a, b = columns(room_sql), columns(mig_sql)
# v4 -> v5: ALTER TABLE ... ADD COLUMN statements append columns to the v4 table.
for alter in re.findall(r'const val ADD_\w+_V5 = "((?:[^"\\]|\\.)*)"', src):
    m2 = re.match(r"ALTER TABLE `scraping_configs` ADD COLUMN `(\w+)` (\w+)(.*)", alter)
    if not m2:
        print('unparsed v5 ALTER:', alter); sys.exit(1)
    print('Migration v5 column:', alter)
    b[0][m2.group(1)] = (m2.group(2).upper(), 'NOT NULL' in m2.group(3).upper())
ok = a == b
if not ok:
    print('MISMATCH')
    for k in sorted(set(a[0]) | set(b[0])):
        if a[0].get(k) != b[0].get(k):
            print('  ', k, 'room=', a[0].get(k), 'migration=', b[0].get(k))
    print('  pk room=', a[1], 'migration=', b[1])
sa = entities['site_analysis']
for f in sa['fields']:
    if f['columnName'] in ('analysisMetadata', 'verificationStatus', 'verificationReason'):
        print('site_analysis field', f)
sys.exit(0 if ok else 1)
