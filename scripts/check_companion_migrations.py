"""Execute migration SQL exported by UpstreamCompanionMigrationTest using stdlib SQLite.

Run the JVM unit tests first, then run this script with Python 3. No user database is opened.
Room/device migration tests remain a separate validation step.
"""
from pathlib import Path
import argparse
import json
import sqlite3
import xml.etree.ElementTree as ET


def create_schema(db, schema):
    for entity in schema['entities']:
        table = entity['tableName']
        db.execute(entity['createSql'].replace('${TABLE_NAME}', table))
        for index in entity.get('indices', []):
            db.execute(index['createSql'].replace('${TABLE_NAME}', table))


def structure(db):
    result = {}
    for (table,) in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"):
        columns = sorted(tuple(row[1:]) for row in db.execute(f'PRAGMA table_info(`{table}`)'))
        indexes = sorted((row[1], row[2], tuple(c[2] for c in db.execute(f'PRAGMA index_info(`{row[1]}`)')))
                         for row in db.execute(f'PRAGMA index_list(`{table}`)') if row[3] != 'pk')
        foreign_keys = sorted(tuple(row[2:]) for row in db.execute(f'PRAGMA foreign_key_list(`{table}`)'))
        result[table] = {'columns': columns, 'indexes': indexes, 'foreignKeys': foreign_keys}
    return result


def insert_sample(db, table, overrides):
    values = {}
    for _, name, affinity, not_null, default, primary_key in db.execute(f'PRAGMA table_info(`{table}`)'):
        if name in overrides:
            values[name] = overrides[name]
        elif primary_key or (not_null and default is None):
            values[name] = 0 if affinity in ('INTEGER', 'REAL') else 'synthetic-' + name
    names = ','.join(f'`{name}`' for name in values)
    db.execute(f"INSERT INTO `{table}` ({names}) VALUES ({','.join('?' for _ in values)})", list(values.values()))


def verify(root, results):
    xml = ET.parse(results / 'TEST-com.nekobot.app.data.local.db.UpstreamCompanionMigrationTest.xml').getroot()
    assert xml.attrib['failures'] == '0' and xml.attrib['errors'] == '0'
    output = xml.findtext('system-out', '')
    migrations = json.loads(next(line.split('=', 1)[1] for line in output.splitlines() if line.startswith('NEKOBOT_MIGRATION_SQL=')))
    triggers = json.loads(next(line.split('=', 1)[1] for line in output.splitlines() if line.startswith('NEKOBOT_COMPANION_TRIGGERS=')))
    schemas = root / 'app/schemas/com.nekobot.app.data.local.db.NekobotDatabase'
    target = json.loads((schemas / '53.json').read_text(encoding='utf-8'))['database']
    fresh = sqlite3.connect(':memory:')
    create_schema(fresh, target)
    for sql in triggers: fresh.executescript(sql)
    expected = structure(fresh)
    checks = []
    for start in range(48, 53):
        db = sqlite3.connect(':memory:')
        db.execute('PRAGMA foreign_keys=ON')
        original = json.loads((schemas / f'{start}.json').read_text(encoding='utf-8'))['database']
        create_schema(db, original)
        insert_sample(db, 'local_characters', {'id': 'character-1', 'name': 'Synthetic character'})
        insert_sample(db, 'local_sessions', {'id': 'session-1', 'character_id': 'character-1', 'name': 'Synthetic session'})
        insert_sample(db, 'local_messages', {'id': 'message-1', 'session_id': 'session-1', 'role': 'user', 'content': 'Synthetic original message'})
        insert_sample(db, 'local_character_memories', {'id': 'author-memory', 'character_id': 'character-1', 'content': 'Original author memory'})
        insert_sample(db, 'local_experience_archives', {'id': 'archive-1', 'session_id': 'session-1',
                      'start_message_id': 'message-1', 'end_message_id': 'message-1', 'summary': 'Original manual archive',
                      'summary_edited': 1, 'tags_edited': 1})
        insert_sample(db, 'local_experience_sources', {'archive_id': 'archive-1', 'message_id': 'message-1'})
        if start >= 49:
            insert_sample(db, 'local_subagent_tasks', {'task_id': 'task-1', 'session_id': 'session-1', 'request_key': 'request-1'})
        if start >= 50:
            insert_sample(db, 'local_subagent_messages', {'task_id': 'task-1', 'sequence': 1, 'payload_json': '{}'})
        if start >= 51:
            insert_sample(db, 'local_subagent_tool_calls', {'task_id': 'task-1', 'call_id': 'call-1', 'assistant_sequence': 1})
            insert_sample(db, 'local_agent_notices', {'notice_id': 'notice-1', 'task_id': 'task-1', 'session_id': 'session-1'})
        old_rows = {e['tableName']: (list(db.execute(f'SELECT * FROM `{e["tableName"]}`')),
                    [f['columnName'] for f in e['fields']]) for e in original['entities']}
        db.commit()
        with db:
            for version in range(start, 53):
                for sql in migrations[str(version)]: db.execute(sql)
        for sql in triggers: db.executescript(sql)
        actual = structure(db)
        assert actual == expected, f'v{start} structure mismatch: {[t for t in set(actual)|set(expected) if actual.get(t)!=expected.get(t)]}'
        for table, (rows, columns) in old_rows.items():
            selected = ','.join(f'`{column}`' for column in columns)
            # SQLite SELECT * follows DDL column order, which matches each exported Room schema.
            assert list(db.execute(f'SELECT {selected} FROM `{table}`')) == rows, f'v{start}: changed rows in {table}'
        assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
        assert db.execute('PRAGMA foreign_key_check').fetchall() == []
        assert db.execute("SELECT character_id,summary_edited,tags_edited FROM local_experience_archives WHERE id='archive-1'").fetchone() == ('character-1', 1, 1)
        # Opening twice must keep all guards and data, without duplicate trigger installation.
        for sql in triggers: db.executescript(sql)
        insert_sample(db, 'local_characters', {'id': 'companion-character', 'name': 'Synthetic companion'})
        insert_sample(db, 'local_sessions', {'id': 'companion-session', 'character_id': 'companion-character',
                      'session_mode': 'agent', 'inherit_character': 1, 'long_conversation_enabled': 1, 'companion_mode': 1})
        assert db.execute("SELECT sessionId FROM companion_owners WHERE characterId='companion-character'").fetchone() == ('companion-session',)
        try:
            insert_sample(db, 'local_sessions', {'id': 'duplicate-session', 'character_id': 'companion-character'})
        except sqlite3.IntegrityError:
            pass
        else:
            raise AssertionError('Ownership guard accepted a second session')
        assert db.execute("SELECT companion_mode FROM local_sessions WHERE id='session-1'").fetchone() == (0,)
        # Actual open triggers must enqueue new quotes and invalidate facts/vectors after an edit.
        insert_sample(db, 'companion_scopes', {'id': 'scope-1', 'profile': 'synthetic',
                      'sessionId': 'companion-session', 'characterId': 'companion-character'})
        insert_sample(db, 'local_messages', {'id': 'companion-quote', 'session_id': 'companion-session', 'role': 'user', 'content': 'Original quote'})
        assert db.execute("SELECT messageId FROM companion_pending WHERE scopeId='scope-1'").fetchone() == ('companion-quote',)
        insert_sample(db, 'companion_memories', {'id': 'fact-1', 'scopeId': 'scope-1', 'factKey': 'synthetic-fact', 'current': 1, 'valid': 1})
        insert_sample(db, 'companion_sources', {'memoryId': 'fact-1', 'messageId': 'companion-quote', 'role': 'user'})
        insert_sample(db, 'companion_receipts', {'scopeId': 'scope-1', 'messageId': 'companion-quote'})
        insert_sample(db, 'companion_vectors', {'scopeId': 'scope-1', 'itemType': 'fact', 'itemId': 'fact-1', 'modelKey': 'synthetic-model'})
        db.execute("UPDATE local_messages SET content='Edited quote' WHERE id='companion-quote'")
        assert db.execute("SELECT valid,current FROM companion_memories WHERE id='fact-1'").fetchone() == (0, 0)
        assert db.execute("SELECT COUNT(*) FROM companion_vectors WHERE itemId='fact-1'").fetchone() == (0,)
        checks.append({'from': start, 'to': 53, 'tables': len(actual), 'preservedTables': len(old_rows),
                       'preservedRows': sum(len(rows) for rows, _ in old_rows.values()),
                       'schemaMatchesFresh': True, 'integrity': 'ok', 'foreignKeys': 'ok',
                       'ownershipGuard': 'ok', 'sourceInvalidation': 'ok'})
        db.close()
    return {'checks': checks, 'freshTables': len(expected), 'migrationSqlSource': 'executed JVM Migration.migrate calls',
            'triggerStatements': len(triggers), 'androidRoomExecuted': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--results', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = verify(args.root, args.results or args.root / 'app/build/test-results/testDebugUnitTest')
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output: args.output.write_text(text + '\n', encoding='utf-8')
    print(text)
