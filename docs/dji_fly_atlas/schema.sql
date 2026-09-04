PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS commands (
    id INTEGER PRIMARY KEY,
    cmd_set INTEGER NOT NULL CHECK (cmd_set BETWEEN 0 AND 255),
    cmd_id INTEGER NOT NULL CHECK (cmd_id BETWEEN 0 AND 255),
    name TEXT NOT NULL,
    direction TEXT NOT NULL CHECK (direction IN ('request', 'push', 'bidirectional')),
    operation_type TEXT NOT NULL CHECK (operation_type IN ('read', 'write', 'action', 'push', 'mixed')),
    risk_level TEXT NOT NULL CHECK (risk_level IN ('read_only', 'low', 'medium', 'high', 'destructive')),
    summary TEXT NOT NULL,
    UNIQUE (cmd_set, cmd_id)
);

CREATE TABLE IF NOT EXISTS routes (
    id INTEGER PRIMARY KEY,
    command_id INTEGER NOT NULL REFERENCES commands(id) ON DELETE CASCADE,
    sender TEXT NOT NULL,
    receiver TEXT NOT NULL,
    notes TEXT NOT NULL DEFAULT '',
    UNIQUE (command_id, sender, receiver)
);

CREATE TABLE IF NOT EXISTS payload_fields (
    id INTEGER PRIMARY KEY,
    command_id INTEGER NOT NULL REFERENCES commands(id) ON DELETE CASCADE,
    message_kind TEXT NOT NULL CHECK (message_kind IN ('request', 'response', 'push')),
    byte_offset INTEGER NOT NULL CHECK (byte_offset >= 0),
    byte_size INTEGER CHECK (byte_size IS NULL OR byte_size > 0),
    name TEXT NOT NULL,
    data_type TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    UNIQUE (command_id, message_kind, byte_offset, name)
);

CREATE TABLE IF NOT EXISTS products (
    id INTEGER PRIMARY KEY,
    slug TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS fly_versions (
    id INTEGER PRIMARY KEY,
    version TEXT NOT NULL UNIQUE,
    notes TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS evidence (
    id INTEGER PRIMARY KEY,
    evidence_key TEXT NOT NULL UNIQUE,
    evidence_type TEXT NOT NULL CHECK (evidence_type IN ('source', 'apk', 'firmware', 'capture', 'live_test', 'documentation')),
    path TEXT NOT NULL,
    locator TEXT NOT NULL DEFAULT '',
    sha256 TEXT,
    notes TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS implementations (
    id INTEGER PRIMARY KEY,
    command_id INTEGER NOT NULL REFERENCES commands(id) ON DELETE CASCADE,
    fly_version_id INTEGER REFERENCES fly_versions(id) ON DELETE SET NULL,
    layer TEXT NOT NULL CHECK (layer IN ('ui', 'sdk_key', 'java', 'native', 'firmware')),
    symbol TEXT NOT NULL,
    source_path TEXT NOT NULL DEFAULT '',
    notes TEXT NOT NULL DEFAULT '',
    UNIQUE (command_id, fly_version_id, layer, symbol)
);

CREATE TABLE IF NOT EXISTS product_support (
    id INTEGER PRIMARY KEY,
    command_id INTEGER NOT NULL REFERENCES commands(id) ON DELETE CASCADE,
    product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    fly_version_id INTEGER REFERENCES fly_versions(id) ON DELETE SET NULL,
    support_state TEXT NOT NULL CHECK (support_state IN ('supported', 'unsupported', 'partial', 'unknown')),
    evidence_level TEXT NOT NULL CHECK (evidence_level IN ('observed', 'derived', 'hypothesis')),
    notes TEXT NOT NULL DEFAULT '',
    UNIQUE (command_id, product_id, fly_version_id)
);

CREATE TABLE IF NOT EXISTS observations (
    id INTEGER PRIMARY KEY,
    command_id INTEGER NOT NULL REFERENCES commands(id) ON DELETE CASCADE,
    product_id INTEGER REFERENCES products(id) ON DELETE SET NULL,
    observed_at TEXT NOT NULL,
    result TEXT NOT NULL,
    response_hex TEXT NOT NULL DEFAULT '',
    evidence_level TEXT NOT NULL CHECK (evidence_level IN ('observed', 'derived', 'hypothesis')),
    evidence_id INTEGER REFERENCES evidence(id) ON DELETE SET NULL,
    notes TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS relationships (
    id INTEGER PRIMARY KEY,
    source_ref TEXT NOT NULL,
    relation TEXT NOT NULL,
    target_ref TEXT NOT NULL,
    evidence_id INTEGER REFERENCES evidence(id) ON DELETE SET NULL,
    notes TEXT NOT NULL DEFAULT '',
    UNIQUE (source_ref, relation, target_ref, evidence_id)
);

CREATE INDEX IF NOT EXISTS idx_commands_name ON commands(name);
CREATE INDEX IF NOT EXISTS idx_payload_command ON payload_fields(command_id, message_kind, byte_offset);
CREATE INDEX IF NOT EXISTS idx_observations_command ON observations(command_id, observed_at);
CREATE INDEX IF NOT EXISTS idx_relationships_source ON relationships(source_ref);
CREATE INDEX IF NOT EXISTS idx_relationships_target ON relationships(target_ref);

-- Migration guard for databases created before observations became idempotent.
DELETE FROM observations
WHERE id NOT IN (
    SELECT MIN(id)
    FROM observations
    GROUP BY command_id, product_id, observed_at, result, response_hex, evidence_id
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_observations_identity
ON observations(command_id, product_id, observed_at, result, response_hex, evidence_id);

CREATE VIEW IF NOT EXISTS command_catalog AS
SELECT
    c.id,
    printf('%02X:%02X', c.cmd_set, c.cmd_id) AS command_hex,
    c.name,
    c.direction,
    c.operation_type,
    c.risk_level,
    c.summary
FROM commands AS c;
