const { getDatabase } = require("./database");
const { config } = require("./config");
const { generateToken, nowIso } = require("./utils");

function claimInstanceSlot(instanceId) {
  if (!Number.isFinite(Number(instanceId))) {
    return;
  }

  getDatabase().prepare(`
    UPDATE license_instances
       SET active = 1,
           slot_claimed = 1,
           reset_at = NULL
     WHERE id = ?
  `).run(instanceId);
}

function claimDeviceSlot(deviceId) {
  if (!Number.isFinite(Number(deviceId))) {
    return;
  }

  getDatabase().prepare(`
    UPDATE license_devices
       SET active = 1,
           slot_claimed = 1,
           reset_at = NULL
     WHERE id = ?
  `).run(deviceId);
}

function syncInstanceSlotClaim(instanceId) {
  if (!Number.isFinite(Number(instanceId))) {
    return;
  }

  const db = getDatabase();
  const openSessionCount = db.prepare(`
    SELECT COUNT(*) AS count
      FROM service_sessions
     WHERE instance_id = ?
       AND closed_at IS NULL
  `).get(instanceId).count;

  db.prepare(`
    UPDATE license_instances
       SET slot_claimed = ?
     WHERE id = ?
  `).run(openSessionCount > 0 ? 1 : 0, instanceId);
}

function syncDeviceSlotClaim(deviceId) {
  if (!Number.isFinite(Number(deviceId))) {
    return;
  }

  const db = getDatabase();
  const openSessionCount = db.prepare(`
    SELECT COUNT(*) AS count
      FROM service_sessions
     WHERE device_id = ?
       AND closed_at IS NULL
  `).get(deviceId).count;

  db.prepare(`
    UPDATE license_devices
       SET slot_claimed = ?
     WHERE id = ?
  `).run(openSessionCount > 0 ? 1 : 0, deviceId);
}

function createSession({
  licenseId,
  deviceId,
  instanceId = null,
  instanceHash = "",
  instanceName = "",
  serverName = "",
  pluginVersion = "",
  ipAddress = "",
  heartbeatIntervalSeconds = config.defaultHeartbeatSeconds
}) {
  const db = getDatabase();
  const sessionToken = generateToken(24);
  const timestamp = nowIso();

  db.prepare(`
    INSERT INTO service_sessions (
      session_token,
      license_id,
      device_id,
      instance_id,
      instance_hash,
      instance_name,
      server_name,
      plugin_version,
      ip_address,
      heartbeat_interval_seconds,
      started_at,
      last_heartbeat_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(
    sessionToken,
    licenseId,
    deviceId,
    instanceId,
    instanceHash,
    instanceName,
    serverName,
    pluginVersion,
    ipAddress,
    heartbeatIntervalSeconds,
    timestamp,
    timestamp
  );

  claimDeviceSlot(deviceId);
  claimInstanceSlot(instanceId);

  return {
    token: sessionToken,
    startedAt: timestamp
  };
}

function closeOpenSessionsForInstance(instanceId, reason = "superseded", closedAt = nowIso()) {
  if (!Number.isFinite(Number(instanceId))) {
    return;
  }

  const db = getDatabase();
  const deviceIds = db.prepare(`
    SELECT DISTINCT device_id
      FROM service_sessions
     WHERE instance_id = ?
       AND closed_at IS NULL
       AND device_id IS NOT NULL
  `).all(instanceId).map((row) => row.device_id);

  const result = db.prepare(`
    UPDATE service_sessions
       SET closed_at = ?,
           close_reason = ?,
           stale_closed = 0
     WHERE instance_id = ?
       AND closed_at IS NULL
  `).run(closedAt, reason, instanceId);

  if (result.changes > 0) {
    syncInstanceSlotClaim(instanceId);
    for (const deviceId of deviceIds) {
      syncDeviceSlotClaim(deviceId);
    }
  }
}

function touchSession(sessionToken, updates = {}) {
  const db = getDatabase();
  const timestamp = nowIso();
  const result = db.prepare(`
    UPDATE service_sessions
       SET last_heartbeat_at = ?,
           server_name = COALESCE(NULLIF(?, ''), server_name),
           plugin_version = COALESCE(NULLIF(?, ''), plugin_version),
           ip_address = COALESCE(NULLIF(?, ''), ip_address),
           instance_name = COALESCE(NULLIF(?, ''), instance_name)
     WHERE session_token = ?
       AND closed_at IS NULL
  `).run(
    timestamp,
    updates.serverName || "",
    updates.pluginVersion || "",
    updates.ipAddress || "",
    updates.instanceName || "",
    sessionToken
  );

  if (result.changes === 0) {
    return null;
  }

  return db.prepare("SELECT * FROM service_sessions WHERE session_token = ?").get(sessionToken);
}

function closeSession(sessionToken, reason = "shutdown", options = {}) {
  const db = getDatabase();
  const closedAt = options.closedAt || nowIso();
  const staleClosed = options.stale ? 1 : 0;
  const existing = db.prepare(`
    SELECT instance_id, device_id
      FROM service_sessions
     WHERE session_token = ?
       AND closed_at IS NULL
  `).get(sessionToken);

  if (!existing) {
    return false;
  }

  const result = db.prepare(`
    UPDATE service_sessions
       SET closed_at = ?,
           close_reason = ?,
           stale_closed = ?
     WHERE session_token = ?
       AND closed_at IS NULL
  `).run(closedAt, reason, staleClosed, sessionToken);

  if (result.changes > 0) {
    syncInstanceSlotClaim(existing.instance_id);
    syncDeviceSlotClaim(existing.device_id);
  }

  return result.changes > 0;
}

function closeAllSessions(reason = "shutdown", options = {}) {
  const db = getDatabase();
  const closedAt = options.closedAt || nowIso();
  const staleClosed = options.stale ? 1 : 0;
  const rows = db.prepare(`
    SELECT DISTINCT instance_id, device_id
      FROM service_sessions
     WHERE closed_at IS NULL
       AND instance_id IS NOT NULL
  `).all();

  const result = db.prepare(`
    UPDATE service_sessions
       SET closed_at = ?,
           close_reason = ?,
           stale_closed = ?
     WHERE closed_at IS NULL
  `).run(closedAt, reason, staleClosed);

  for (const row of rows) {
    syncInstanceSlotClaim(row.instance_id);
    syncDeviceSlotClaim(row.device_id);
  }

  return result.changes;
}

function getSessionByToken(sessionToken) {
  return getDatabase().prepare("SELECT * FROM service_sessions WHERE session_token = ?").get(sessionToken);
}

function computeSessionTimeoutAt(session = {}) {
  const lastHeartbeat = Date.parse(session.last_heartbeat_at);
  if (!Number.isFinite(lastHeartbeat)) {
    return Number.NaN;
  }

  const heartbeatWindowMs = ((session.heartbeat_interval_seconds || config.defaultHeartbeatSeconds) + config.sessionStaleGraceSeconds) * 1000;
  const regularTimeoutAt = lastHeartbeat + heartbeatWindowMs;
  const startedAt = Date.parse(session.started_at);
  if (!Number.isFinite(startedAt)) {
    return regularTimeoutAt;
  }

  const untouchedSession = Math.abs(lastHeartbeat - startedAt) < 1000;
  if (!untouchedSession) {
    return regularTimeoutAt;
  }

  const startupGraceTimeoutAt = startedAt + Math.max(config.initialSessionGraceSeconds, config.defaultHeartbeatSeconds) * 1000;
  return Math.max(regularTimeoutAt, startupGraceTimeoutAt);
}

function reconcileSessions() {
  const db = getDatabase();
  const now = Date.now();
  const openSessions = db.prepare(`
    SELECT session_token, started_at, last_heartbeat_at, heartbeat_interval_seconds
      FROM service_sessions
     WHERE closed_at IS NULL
  `).all();

  for (const session of openSessions) {
    const timeoutAt = computeSessionTimeoutAt(session);
    if (!Number.isFinite(timeoutAt)) {
      closeSession(session.session_token, "heartbeat_timeout", { stale: true });
      continue;
    }

    if (now > timeoutAt) {
      closeSession(session.session_token, "heartbeat_timeout", {
        stale: true,
        closedAt: new Date(timeoutAt).toISOString()
      });
    }
  }
}

module.exports = {
  closeAllSessions,
  closeOpenSessionsForInstance,
  closeSession,
  computeSessionTimeoutAt,
  createSession,
  getSessionByToken,
  reconcileSessions,
  syncDeviceSlotClaim,
  touchSession
};
