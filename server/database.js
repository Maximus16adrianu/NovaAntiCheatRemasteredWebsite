const Database = require("better-sqlite3");
const { config, ensureDirectories } = require("./config");

let db;

function getDatabase() {
  if (!db) {
    initializeDatabase();
  }
  return db;
}

function ensureColumn(database, tableName, columnName, definition) {
  const existingColumns = database.prepare(`PRAGMA table_info(${tableName})`).all();
  if (existingColumns.some((column) => column.name === columnName)) {
    return;
  }

  database.exec(`ALTER TABLE ${tableName} ADD COLUMN ${columnName} ${definition}`);
}

function initializeDatabase() {
  if (db) {
    return db;
  }

  ensureDirectories();
  db = new Database(config.dbFilePath);
  db.pragma("journal_mode = WAL");
  db.pragma("foreign_keys = ON");
  db.pragma("busy_timeout = 5000");

  db.exec(`
    CREATE TABLE IF NOT EXISTS licenses (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      license_key TEXT NOT NULL UNIQUE,
      customer_username TEXT NOT NULL DEFAULT '',
      display_name TEXT NOT NULL DEFAULT '',
      license_type TEXT NOT NULL DEFAULT 'monthly',
      expires_at TEXT,
      max_hwids INTEGER NOT NULL DEFAULT 1,
      max_instances INTEGER NOT NULL DEFAULT 1,
      active INTEGER NOT NULL DEFAULT 1,
      notes TEXT NOT NULL DEFAULT '',
      reset_interval_days INTEGER NOT NULL DEFAULT 30,
      next_reset_at TEXT,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS license_devices (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      license_id INTEGER NOT NULL,
      hwid_hash TEXT NOT NULL,
      device_name TEXT NOT NULL DEFAULT 'Unknown Device',
      fingerprint_json TEXT NOT NULL DEFAULT '{}',
      first_seen_at TEXT NOT NULL,
      last_seen_at TEXT NOT NULL,
      last_username TEXT NOT NULL DEFAULT '',
      active INTEGER NOT NULL DEFAULT 1,
      reset_at TEXT,
      FOREIGN KEY (license_id) REFERENCES licenses(id) ON DELETE CASCADE,
      UNIQUE (license_id, hwid_hash)
    );

    CREATE TABLE IF NOT EXISTS license_instances (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      license_id INTEGER NOT NULL,
      device_id INTEGER NOT NULL,
      instance_hash TEXT NOT NULL,
      instance_uuid TEXT NOT NULL DEFAULT '',
      instance_name TEXT NOT NULL DEFAULT 'Unknown Instance',
      fingerprint_json TEXT NOT NULL DEFAULT '{}',
      first_seen_at TEXT NOT NULL,
      last_seen_at TEXT NOT NULL,
      last_server_name TEXT NOT NULL DEFAULT '',
      active INTEGER NOT NULL DEFAULT 1,
      reset_at TEXT,
      FOREIGN KEY (license_id) REFERENCES licenses(id) ON DELETE CASCADE,
      FOREIGN KEY (device_id) REFERENCES license_devices(id) ON DELETE CASCADE,
      UNIQUE (license_id, device_id, instance_hash)
    );

    CREATE TABLE IF NOT EXISTS service_sessions (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      session_token TEXT NOT NULL UNIQUE,
      license_id INTEGER NOT NULL,
      device_id INTEGER NOT NULL,
      instance_id INTEGER,
      instance_hash TEXT NOT NULL DEFAULT '',
      instance_name TEXT NOT NULL DEFAULT '',
      server_name TEXT NOT NULL DEFAULT '',
      plugin_version TEXT NOT NULL DEFAULT '',
      ip_address TEXT NOT NULL DEFAULT '',
      heartbeat_interval_seconds INTEGER NOT NULL DEFAULT 60,
      started_at TEXT NOT NULL,
      last_heartbeat_at TEXT NOT NULL,
      closed_at TEXT,
      close_reason TEXT,
      stale_closed INTEGER NOT NULL DEFAULT 0,
      FOREIGN KEY (license_id) REFERENCES licenses(id) ON DELETE CASCADE,
      FOREIGN KEY (device_id) REFERENCES license_devices(id) ON DELETE CASCADE,
      FOREIGN KEY (instance_id) REFERENCES license_instances(id) ON DELETE SET NULL
    );

    CREATE TABLE IF NOT EXISTS audit_logs (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      license_id INTEGER,
      device_id INTEGER,
      actor TEXT NOT NULL,
      action TEXT NOT NULL,
      details_json TEXT NOT NULL DEFAULT '{}',
      created_at TEXT NOT NULL,
      FOREIGN KEY (license_id) REFERENCES licenses(id) ON DELETE SET NULL,
      FOREIGN KEY (device_id) REFERENCES license_devices(id) ON DELETE SET NULL
    );
  `);

  ensureColumn(db, "licenses", "license_type", "TEXT NOT NULL DEFAULT 'monthly'");
  ensureColumn(db, "licenses", "expires_at", "TEXT");
  ensureColumn(db, "licenses", "max_instances", "INTEGER NOT NULL DEFAULT 1");
  ensureColumn(db, "service_sessions", "instance_id", "INTEGER");
  ensureColumn(db, "service_sessions", "instance_hash", "TEXT NOT NULL DEFAULT ''");
  ensureColumn(db, "service_sessions", "instance_name", "TEXT NOT NULL DEFAULT ''");

  db.exec(`
    CREATE INDEX IF NOT EXISTS idx_licenses_key ON licenses (license_key);
    CREATE INDEX IF NOT EXISTS idx_licenses_expiry ON licenses (active, expires_at);
    CREATE INDEX IF NOT EXISTS idx_devices_license_active ON license_devices (license_id, active);
    CREATE INDEX IF NOT EXISTS idx_instances_license_active ON license_instances (license_id, active);
    CREATE INDEX IF NOT EXISTS idx_instances_device_active ON license_instances (device_id, active);
    CREATE INDEX IF NOT EXISTS idx_sessions_open ON service_sessions (closed_at, license_id, device_id);
    CREATE INDEX IF NOT EXISTS idx_sessions_instance_open ON service_sessions (closed_at, instance_id);
    CREATE INDEX IF NOT EXISTS idx_audit_logs_license ON audit_logs (license_id, created_at);
  `);

  return db;
}

function closeDatabase() {
  if (!db) {
    return;
  }
  db.close();
  db = null;
}

module.exports = {
  closeDatabase,
  getDatabase,
  initializeDatabase
};
