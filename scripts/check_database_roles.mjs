// Owned loopback test database only. Creates two temporary LOGIN roles, checks database
// denials, and runs the existing fixture acceptance chains under the separate identities.
// Requires owner-prepared platform migrations, built bootJars/Web/Rust and explicit test storage.
import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { spawn } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const root = fileURLToPath(new URL('../', import.meta.url))
const url = new URL((process.env.OPSWEAVE_TEST_JDBC_URL ?? '').replace(/^jdbc:/, ''))
assert(['127.0.0.1', '[::1]'].includes(url.hostname) && !url.username && !url.password && !url.search && !url.hash, 'Owned loopback PG URL required')
const database = url.pathname.slice(1)
assert(/^[a-zA-Z0-9_]+$/.test(database) && url.port, 'Explicit test database and port required')
const owner = process.env.OPSWEAVE_TEST_JDBC_USER, ownerPassword = process.env.OPSWEAVE_TEST_JDBC_PASSWORD
assert(owner && ownerPassword !== undefined, 'Explicit test owner credentials required')
const psql = process.env.OPSWEAVE_TEST_PSQL_EXECUTABLE
assert(psql && path.isAbsolute(psql), 'Explicit absolute native psql path required')
const suffix = randomBytes(8).toString('hex')
const platform = { name: 'ow_platform_' + suffix, password: randomBytes(32).toString('hex') }
const history = { name: 'ow_history_' + suffix, password: randomBytes(32).toString('hex') }
const literal = value => "'" + value.replaceAll("'", "''") + "'"
const identifier = value => '"' + value.replaceAll('"', '""') + '"'
const schemaOwner = { name: owner, password: ownerPassword }

// SQL arrives only on stdin; credentials never enter command arguments, saved reports or errors.
// psql's sqlstate verbosity and a fixed error message keep query text/credentials out of output.
async function sql(query, identity = schemaOwner, vars = []) {
  const args = ['-X', '-w', '-h', url.hostname.replace(/^\[|\]$/g, ''), '-p', url.port, '-d', database,
    '-U', identity.name, '-At', '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=sqlstate', ...vars]
  return await new Promise((resolve, reject) => {
    const child = spawn(psql, args, { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'],
      env: { ...process.env, PGPASSWORD: identity.password, PGCONNECT_TIMEOUT: '3' } })
    let out = '', err = '', tooLarge = false, timedOut = false
    const timer = setTimeout(() => { timedOut = true; child.kill() }, 15000)
    for (const [stream, save] of [[child.stdout, chunk => { out += chunk }], [child.stderr, chunk => { err += chunk }]]) {
      stream.setEncoding('utf8'); stream.on('data', chunk => { save(chunk); if (out.length + err.length > 1048576) { tooLarge = true; child.kill() } })
    }
    child.on('error', () => { clearTimeout(timer); reject(new Error('Owned PostgreSQL client launch failed')) })
    child.stdin.on('error', () => {})
    child.on('close', code => {
      clearTimeout(timer)
      if (tooLarge || timedOut) return reject(new Error('Owned PostgreSQL check exceeded its bound'))
      resolve({ code, output: out.trim(), denied: /(?:ERROR|错误):\s+42501\b/.test(err) || /\b42501\b/.test(err) })
    })
    child.stdin.end(query)
  })
}
async function ok(query, identity, vars) {
  const result = await sql(query, identity, vars)
  assert.equal(result.code, 0, 'Owned PostgreSQL check failed; raw server output suppressed')
  return result.output
}
async function denied(query, identity) {
  // A accidentally permitted probe rolls back rather than modifying any business object.
  const result = await sql('BEGIN;\n' + query + ';\nROLLBACK;\n', identity)
  assert(result.code !== 0 && result.denied, 'Expected database privilege denial (SQLSTATE 42501)')
}
async function run(script, args, env) {
  await new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [script, ...args], { cwd: root, env, windowsHide: true, stdio: 'inherit' })
    child.once('error', () => reject(new Error('Fixture acceptance child failed to start')))
    child.once('exit', code => code === 0 ? resolve() : reject(new Error('Fixture acceptance chain failed')))
  })
}
const created = []
try {
  // Explicit owner bootstrap for the Worker. Normal Worker startup below is verify-only.
  for (const file of ['V001__history_checkpoint.sql', 'V002__history_checkpoint_lease.sql']) {
    await ok('BEGIN; SELECT pg_advisory_xact_lock(1875725101);\n' + await readFile(path.join(root, 'db/migrations/ingestion', file), 'utf8') + '\nCOMMIT;')
  }
  for (const role of [platform, history]) {
    await ok("SET log_statement='none'; SET log_min_error_statement='panic'; CREATE ROLE " + identifier(role.name) +
      ' LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD ' + literal(role.password) + ';')
    created.push(role)
  }
  await ok(await readFile(path.join(root, 'db/security/runtime-grants.sql'), 'utf8'), schemaOwner,
    ['-v', 'platform_role=' + platform.name, '-v', 'history_role=' + history.name])
  // CONNECT is separate from business table grants; PUBLIC privileges are inspected, not silently changed.
  await ok('GRANT CONNECT ON DATABASE ' + identifier(database) + ' TO ' + identifier(platform.name) + ', ' + identifier(history.name) + ';')
  for (const role of [platform, history]) {
    assert.equal(await ok('SELECT current_user', role), role.name)
    assert.equal(await ok('SELECT NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolinherit OR rolreplication OR rolbypassrls) FROM pg_roles WHERE rolname=current_user', role), 't')
    assert.equal(await ok('SELECT count(*) FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=current_user)', role), '0')
    await denied('CREATE SCHEMA ' + identifier('ow_probe_' + suffix), role)
    await denied('SET ROLE ' + identifier(owner), role)
  }
  for (const [role, schemas] of [[platform, ['ingestion']], [history, ['inventory', 'integration', 'telemetry', 'alerting', 'incident', 'ai_control', 'audit']]]) {
    assert.equal(await ok("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relkind IN ('r','p','v','m') AND n.nspname IN (" + schemas.map(literal).join(',') + ") AND has_table_privilege(current_user,c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')", role), '0')
  }
  await denied('SELECT * FROM ingestion.history_checkpoint LIMIT 0', platform)
  await denied('INSERT INTO integration.schema_migration(id) VALUES (' + literal('deny-' + suffix) + ')', platform)
  await denied('DELETE FROM integration.raw_record_metadata WHERE false', platform)
  await denied('TRUNCATE inventory.entity', platform)
  await denied('CREATE TABLE inventory.' + identifier('ow_probe_' + suffix) + '(id integer)', platform)
  await denied('ALTER TABLE inventory.entity ADD COLUMN ' + identifier('ow_probe_' + suffix) + ' integer', platform)
  await denied('SELECT * FROM inventory.entity LIMIT 0', history)
  await denied('SELECT * FROM ai_control.ai_insight LIMIT 0', history)
  await denied('DELETE FROM ingestion.history_checkpoint WHERE false', history)
  await denied('ALTER TABLE ingestion.history_checkpoint ADD COLUMN ' + identifier('ow_probe_' + suffix) + ' integer', history)
  await denied('CREATE TABLE ingestion.' + identifier('ow_probe_' + suffix) + '(id integer)', history)
  console.log('PASS: Separate PG LOGIN identities have no owner membership/elevated flags; cross-module reads, protected DDL and forbidden writes return 42501.')
  const env = { ...process.env, OPSWEAVE_TEST_JDBC_USER: platform.name, OPSWEAVE_TEST_JDBC_PASSWORD: platform.password,
    OPSWEAVE_TEST_HISTORY_JDBC_USER: history.name, OPSWEAVE_TEST_HISTORY_JDBC_PASSWORD: history.password }
  await run('scripts/check_metrics_stack.mjs', ['--pipeline', '--runtime'], env)
  await run('scripts/check_oidc_stack.mjs', ['--history-service'], env)
  console.log('PASS: Both local fixture acceptance chains ran with separate restricted PG identities; Worker used schema-mode=verify. This is not real Zabbix/model/IdP acceptance.')
} finally {
  // Exact random roles owned by this run only; business tables/data remain owned by the original owner.
  // DROP OWNED removes these roles' grants in this database. Successful negative probes always rolled back.
  for (const role of created.reverse()) {
    await ok('DROP OWNED BY ' + identifier(role.name) + '; DROP ROLE ' + identifier(role.name) + ';')
  }
}
