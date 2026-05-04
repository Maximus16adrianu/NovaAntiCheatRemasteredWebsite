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
      license_plan TEXT NOT NULL DEFAULT 'basic',
      cloud_player_slots INTEGER NOT NULL DEFAULT 0,
      expires_at TEXT,
      max_hwids INTEGER NOT NULL DEFAULT 1,
      max_instances INTEGER NOT NULL DEFAULT 1,
      active INTEGER NOT NULL DEFAULT 1,
      notes TEXT NOT NULL DEFAULT '',
      webhook_url TEXT NOT NULL DEFAULT '',
      webhook_events_json TEXT NOT NULL DEFAULT '{}',
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
      slot_claimed INTEGER NOT NULL DEFAULT 0,
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
      slot_claimed INTEGER NOT NULL DEFAULT 0,
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

    CREATE TABLE IF NOT EXISTS download_jars (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      display_name TEXT NOT NULL,
      notes TEXT NOT NULL DEFAULT '',
      changelog TEXT NOT NULL DEFAULT '',
      supported_versions TEXT NOT NULL DEFAULT '',
      release_channel TEXT NOT NULL DEFAULT 'stable',
      access_scope TEXT NOT NULL DEFAULT 'buyers',
      recommended INTEGER NOT NULL DEFAULT 0,
      stored_name TEXT NOT NULL UNIQUE,
      original_name TEXT NOT NULL DEFAULT '',
      file_size INTEGER NOT NULL DEFAULT 0,
      mime_type TEXT NOT NULL DEFAULT 'application/java-archive',
      sort_order INTEGER NOT NULL DEFAULT 0,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS jar_download_rate_limits (
      ip_key TEXT PRIMARY KEY,
      cooldown_until TEXT NOT NULL,
      last_download_at TEXT NOT NULL,
      last_jar_id INTEGER,
      FOREIGN KEY (last_jar_id) REFERENCES download_jars(id) ON DELETE SET NULL
    );

    CREATE TABLE IF NOT EXISTS reset_lookup_rate_limits (
      ip_key TEXT PRIMARY KEY,
      cooldown_until TEXT NOT NULL,
      last_lookup_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS system_settings (
      setting_key TEXT PRIMARY KEY,
      value_json TEXT NOT NULL DEFAULT '{}',
      updated_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS webhook_notifications (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      license_id INTEGER,
      event_key TEXT NOT NULL,
      dedupe_key TEXT NOT NULL UNIQUE,
      status TEXT NOT NULL DEFAULT 'queued',
      payload_json TEXT NOT NULL DEFAULT '{}',
      created_at TEXT NOT NULL,
      delivered_at TEXT,
      response_status INTEGER,
      response_body TEXT NOT NULL DEFAULT '',
      FOREIGN KEY (license_id) REFERENCES licenses(id) ON DELETE SET NULL
    );

    CREATE TABLE IF NOT EXISTS license_device_blacklist (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      license_id INTEGER NOT NULL,
      hwid_hash TEXT NOT NULL,
      device_name TEXT NOT NULL DEFAULT '',
      details_json TEXT NOT NULL DEFAULT '{}',
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      FOREIGN KEY (license_id) REFERENCES licenses(id) ON DELETE CASCADE,
      UNIQUE (license_id, hwid_hash)
    );

    CREATE TABLE IF NOT EXISTS license_instance_blacklist (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      license_id INTEGER NOT NULL,
      instance_uuid TEXT NOT NULL DEFAULT '',
      instance_hash TEXT NOT NULL DEFAULT '',
      instance_name TEXT NOT NULL DEFAULT '',
      last_server_name TEXT NOT NULL DEFAULT '',
      details_json TEXT NOT NULL DEFAULT '{}',
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      FOREIGN KEY (license_id) REFERENCES licenses(id) ON DELETE CASCADE,
      UNIQUE (license_id, instance_uuid)
    );
  `);

  ensureColumn(db, "licenses", "license_type", "TEXT NOT NULL DEFAULT 'monthly'");
  ensureColumn(db, "licenses", "license_plan", "TEXT NOT NULL DEFAULT 'basic'");
  ensureColumn(db, "licenses", "cloud_player_slots", "INTEGER NOT NULL DEFAULT 0");
  ensureColumn(db, "licenses", "expires_at", "TEXT");
  ensureColumn(db, "licenses", "max_instances", "INTEGER NOT NULL DEFAULT 1");
  ensureColumn(db, "licenses", "webhook_url", "TEXT NOT NULL DEFAULT ''");
  ensureColumn(db, "licenses", "webhook_events_json", "TEXT NOT NULL DEFAULT '{}'");
  ensureColumn(db, "license_devices", "slot_claimed", "INTEGER NOT NULL DEFAULT 0");
  ensureColumn(db, "service_sessions", "instance_id", "INTEGER");
  ensureColumn(db, "service_sessions", "instance_hash", "TEXT NOT NULL DEFAULT ''");
  ensureColumn(db, "service_sessions", "instance_name", "TEXT NOT NULL DEFAULT ''");
  ensureColumn(db, "license_instances", "slot_claimed", "INTEGER NOT NULL DEFAULT 0");
  ensureColumn(db, "download_jars", "notes", "TEXT NOT NULL DEFAULT ''");
  ensureColumn(db, "download_jars", "changelog", "TEXT NOT NULL DEFAULT ''");
  ensureColumn(db, "download_jars", "supported_versions", "TEXT NOT NULL DEFAULT ''");
  ensureColumn(db, "download_jars", "release_channel", "TEXT NOT NULL DEFAULT 'stable'");
  ensureColumn(db, "download_jars", "access_scope", "TEXT NOT NULL DEFAULT 'buyers'");
  ensureColumn(db, "download_jars", "recommended", "INTEGER NOT NULL DEFAULT 0");
  ensureColumn(db, "download_jars", "original_name", "TEXT NOT NULL DEFAULT ''");
  ensureColumn(db, "download_jars", "mime_type", "TEXT NOT NULL DEFAULT 'application/java-archive'");
  ensureColumn(db, "download_jars", "sort_order", "INTEGER NOT NULL DEFAULT 0");

  db.exec(`
    UPDATE licenses
       SET license_plan = CASE
         WHEN lower(trim(license_type)) = 'lifetime' THEN 'basic'
         WHEN lower(trim(license_plan)) = 'pro' THEN 'pro'
         ELSE 'basic'
       END,
           cloud_player_slots = CASE
         WHEN lower(trim(license_type)) = 'lifetime' THEN 0
         WHEN lower(trim(license_plan)) = 'pro' AND cloud_player_slots <= 10 THEN 10
         WHEN lower(trim(license_plan)) = 'pro' AND cloud_player_slots <= 25 THEN 25
         WHEN lower(trim(license_plan)) = 'pro' THEN 50
         ELSE 0
       END
  `);

  db.exec(`
    UPDATE license_devices
       SET slot_claimed = CASE
         WHEN EXISTS (
           SELECT 1
             FROM service_sessions s
            WHERE s.device_id = license_devices.id
              AND s.closed_at IS NULL
         ) THEN 1
         ELSE 0
       END
  `);

  db.exec(`
    UPDATE license_instances
       SET slot_claimed = CASE
         WHEN EXISTS (
           SELECT 1
             FROM service_sessions s
            WHERE s.instance_id = license_instances.id
              AND s.closed_at IS NULL
         ) THEN 1
         ELSE 0
       END
  `);

  db.exec(`
    UPDATE download_jars
       SET release_channel = CASE
         WHEN lower(trim(release_channel)) IN ('stable', 'beta', 'legacy') THEN lower(trim(release_channel))
         ELSE 'stable'
       END,
           recommended = CASE
         WHEN recommended = 1 THEN 1
         ELSE 0
       END
  `);

  db.exec(`
    CREATE INDEX IF NOT EXISTS idx_licenses_key ON licenses (license_key);
    CREATE INDEX IF NOT EXISTS idx_licenses_expiry ON licenses (active, expires_at);
    CREATE INDEX IF NOT EXISTS idx_devices_license_active ON license_devices (license_id, active);
    CREATE INDEX IF NOT EXISTS idx_devices_license_claimed ON license_devices (license_id, active, slot_claimed);
    CREATE INDEX IF NOT EXISTS idx_instances_license_active ON license_instances (license_id, active);
    CREATE INDEX IF NOT EXISTS idx_instances_device_active ON license_instances (device_id, active);
    CREATE INDEX IF NOT EXISTS idx_instances_license_claimed ON license_instances (license_id, active, slot_claimed);
    CREATE INDEX IF NOT EXISTS idx_sessions_open ON service_sessions (closed_at, license_id, device_id);
    CREATE INDEX IF NOT EXISTS idx_sessions_instance_open ON service_sessions (closed_at, instance_id);
    CREATE INDEX IF NOT EXISTS idx_audit_logs_license ON audit_logs (license_id, created_at);
    CREATE INDEX IF NOT EXISTS idx_audit_logs_created ON audit_logs (created_at, id);
    CREATE INDEX IF NOT EXISTS idx_webhook_notifications_license ON webhook_notifications (license_id, created_at);
    CREATE INDEX IF NOT EXISTS idx_download_jars_sort ON download_jars (sort_order, id);
    CREATE INDEX IF NOT EXISTS idx_device_blacklist_license ON license_device_blacklist (license_id, hwid_hash);
    CREATE INDEX IF NOT EXISTS idx_instance_blacklist_license ON license_instance_blacklist (license_id, instance_uuid, instance_hash);
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
