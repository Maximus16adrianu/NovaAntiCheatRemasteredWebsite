const crypto = require("crypto");
const { config } = require("./config");
const { getDatabase } = require("./database");
const { buildHwidFingerprint } = require("./hwid");
const { buildInstanceFingerprint } = require("./instance");
const {
  closeOpenSessionsForInstance,
  closeSession,
  createSession,
  getSessionByToken,
  reconcileSessions,
  touchSession
} = require("./sessions");
const {
  computeSuggestedPricing,
  getDaysRemaining,
  getLicenseTypeDefinition,
  isLicenseExpired,
  normalizeLicenseType,
  resolveLicenseExpiry
} = require("./pricing");
const {
  HttpError,
  addCalendarMonths,
  addCalendarYears,
  addDays,
  clampInteger,
  endOfLocalDay,
  generateLicenseKey,
  normalizeIdentifier,
  normalizeText,
  nowIso,
  safeJsonParse
} = require("./utils");

const RESET_LOOKUP_COOLDOWN_MS = 10_000;

const LICENSE_SELECT = `
  SELECT l.*,
         (SELECT COUNT(*) FROM license_devices d WHERE d.license_id = l.id AND d.active = 1) AS active_device_count,
         (SELECT COUNT(*) FROM license_instances i WHERE i.license_id = l.id AND i.active = 1) AS active_instance_count,
         (SELECT COUNT(DISTINCT s.instance_id)
            FROM service_sessions s
           WHERE s.license_id = l.id
             AND s.closed_at IS NULL
             AND s.instance_id IS NOT NULL) AS online_instance_count,
         (SELECT COUNT(*) FROM service_sessions s WHERE s.license_id = l.id AND s.closed_at IS NULL) AS open_session_count
    FROM licenses l
`;

function normalizeIpAddress(ipAddress) {
  const raw = normalizeText(ipAddress, "unknown");
  if (!raw) {
    return "unknown";
  }
  if (raw === "::1") {
    return "127.0.0.1";
  }
  return raw.startsWith("::ffff:") ? raw.slice(7) : raw;
}

function hashIpAddress(ipAddress) {
  return crypto.createHash("sha256").update(normalizeIpAddress(ipAddress)).digest("hex");
}

function claimResetLookupAttempt(ipAddress) {
  const database = getDatabase();
  const ipKey = hashIpAddress(ipAddress);
  const now = Date.now();
  const cooldownUntil = new Date(now + RESET_LOOKUP_COOLDOWN_MS).toISOString();
  const timestamp = new Date(now).toISOString();

  database.transaction(() => {
    const existing = database.prepare(`
      SELECT cooldown_until
        FROM reset_lookup_rate_limits
       WHERE ip_key = ?
    `).get(ipKey);

    const remaining = existing?.cooldown_until ? (Date.parse(existing.cooldown_until) - now) : 0;
    if (remaining > 0) {
      throw new HttpError(429, `You can look up another license in ${Math.ceil(remaining / 1000)} seconds.`, {
        cooldownRemainingMs: remaining,
        cooldownWindowMs: RESET_LOOKUP_COOLDOWN_MS
      });
    }

    if (existing) {
      database.prepare(`
        UPDATE reset_lookup_rate_limits
           SET cooldown_until = ?,
               last_lookup_at = ?
         WHERE ip_key = ?
      `).run(cooldownUntil, timestamp, ipKey);
    } else {
      database.prepare(`
        INSERT INTO reset_lookup_rate_limits (
          ip_key,
          cooldown_until,
          last_lookup_at
        ) VALUES (?, ?, ?)
      `).run(ipKey, cooldownUntil, timestamp);
    }
  })();
}

function serializeLicense(row) {
  if (!row) {
    return null;
  }

  const nextResetAt = row.next_reset_at || null;
  const nextResetTimestamp = nextResetAt ? Date.parse(nextResetAt) : 0;
  const licenseType = normalizeLicenseType(row.license_type);
  const expired = isLicenseExpired(row.expires_at);
  const maxSlots = Math.max(row.max_hwids || 1, row.max_instances || 1);
  const pricing = computeSuggestedPricing({
    licenseType,
    maxHwids: maxSlots,
    maxInstances: maxSlots
  });

  return {
    id: row.id,
    key: row.license_key,
    customerUsername: row.customer_username,
    displayName: row.display_name,
    licenseType,
    licenseTypeLabel: getLicenseTypeDefinition(licenseType).label,
    expiresAt: row.expires_at || null,
    expired,
    daysRemaining: getDaysRemaining(row.expires_at),
    maxHwids: row.max_hwids,
    maxInstances: row.max_instances,
    maxSlots,
    active: Boolean(row.active),
    notes: row.notes,
    resetIntervalDays: row.reset_interval_days,
    nextResetAt,
    canResetNow: !nextResetTimestamp || nextResetTimestamp <= Date.now(),
    activeDeviceCount: row.active_device_count ?? 0,
    activeInstanceCount: row.active_instance_count ?? 0,
    onlineInstanceCount: row.online_instance_count ?? 0,
    openSessionCount: row.open_session_count ?? 0,
    pricing,
    createdAt: row.created_at,
    updatedAt: row.updated_at
  };
}

function serializeDevice(row) {
  const fingerprint = safeJsonParse(row.fingerprint_json, {});
  return {
    id: row.id,
    licenseId: row.license_id,
    hwidHash: row.hwid_hash,
    deviceName: row.device_name,
    firstSeenAt: row.first_seen_at,
    lastSeenAt: row.last_seen_at,
    lastUsername: row.last_username,
    active: Boolean(row.active),
    resetAt: row.reset_at,
    online: (row.open_session_count || 0) > 0,
    openSessionCount: row.open_session_count || 0,
    fingerprint
  };
}

function serializeInstance(row) {
  const fingerprint = safeJsonParse(row.fingerprint_json, {});
  return {
    id: row.id,
    licenseId: row.license_id,
    deviceId: row.device_id,
    deviceName: row.device_name || "Unknown Device",
    hwidHash: row.hwid_hash || "",
    instanceHash: row.instance_hash,
    instanceUuid: row.instance_uuid,
    instanceName: row.instance_name,
    firstSeenAt: row.first_seen_at,
    lastSeenAt: row.last_seen_at,
    lastServerName: row.last_server_name,
    active: Boolean(row.active),
    resetAt: row.reset_at,
    online: (row.open_session_count || 0) > 0,
    openSessionCount: row.open_session_count || 0,
    fingerprint
  };
}

function sanitizeServerInfo(server = {}) {
  return {
    serverName: normalizeText(server.serverName || server.name, "Unknown Server"),
    pluginVersion: normalizeText(server.pluginVersion || server.version, "unknown")
  };
}

function logAudit({ licenseId = null, deviceId = null, actor, action, details = {} }) {
  const db = getDatabase();
  db.prepare(`
    INSERT INTO audit_logs (license_id, device_id, actor, action, details_json, created_at)
    VALUES (?, ?, ?, ?, ?, ?)
  `).run(licenseId, deviceId, actor, action, JSON.stringify(details), nowIso());
}

function getLicenseByKey(licenseKey) {
  return getDatabase().prepare(`${LICENSE_SELECT} WHERE l.license_key = ?`).get(normalizeText(licenseKey).toUpperCase());
}

function getLicenseById(licenseId) {
  return getDatabase().prepare(`${LICENSE_SELECT} WHERE l.id = ?`).get(licenseId);
}

function getSelfServiceResetLicense(licenseKey, username) {
  const license = getLicenseByKey(licenseKey);
  const normalizedUsername = normalizeIdentifier(username);

  if (!normalizedUsername) {
    throw new HttpError(400, "Enter the license user.");
  }

  if (!license || !license.customer_username || normalizeIdentifier(license.customer_username) !== normalizedUsername) {
    throw new HttpError(404, "License key and user do not match.");
  }

  if (!license.active) {
    throw new HttpError(403, "This license is disabled.");
  }

  return license;
}

function assertLicenseForActivation(license) {
  if (!license) {
    throw new HttpError(404, "Unknown license key.");
  }
  if (!license.active) {
    throw new HttpError(403, "This license is disabled.");
  }
  if (isLicenseExpired(license.expires_at)) {
    throw new HttpError(403, "This license has expired.", {
      expiresAt: license.expires_at
    });
  }
}

function listLicenses() {
  reconcileSessions();
  return getDatabase().prepare(`
    ${LICENSE_SELECT}
   ORDER BY l.created_at DESC
  `).all().map(serializeLicense);
}

function listLicenseDevices(licenseId) {
  reconcileSessions();
  return getDatabase().prepare(`
    SELECT d.*,
           (SELECT COUNT(*) FROM service_sessions s WHERE s.device_id = d.id AND s.closed_at IS NULL) AS open_session_count
      FROM license_devices d
     WHERE d.license_id = ?
     ORDER BY d.active DESC, d.last_seen_at DESC
  `).all(licenseId).map(serializeDevice);
}

function listLicenseInstances(licenseId) {
  reconcileSessions();
  return getDatabase().prepare(`
    SELECT i.*,
           d.device_name,
           d.hwid_hash,
           (SELECT COUNT(*) FROM service_sessions s WHERE s.instance_id = i.id AND s.closed_at IS NULL) AS open_session_count
      FROM license_instances i
      LEFT JOIN license_devices d ON d.id = i.device_id
     WHERE i.license_id = ?
     ORDER BY i.active DESC, i.last_seen_at DESC
  `).all(licenseId).map(serializeInstance);
}

function listRecentSessions(limit = 150) {
  reconcileSessions();
  return getDatabase().prepare(`
    SELECT s.*,
           l.license_key,
           l.customer_username,
           d.device_name,
           i.instance_name AS registered_instance_name
      FROM service_sessions s
      JOIN licenses l ON l.id = s.license_id
      JOIN license_devices d ON d.id = s.device_id
 LEFT JOIN license_instances i ON i.id = s.instance_id
     ORDER BY COALESCE(s.closed_at, s.last_heartbeat_at) DESC
     LIMIT ?
  `).all(clampInteger(limit, 10, 500, 150)).map((row) => ({
    id: row.id,
    sessionToken: row.session_token,
    licenseId: row.license_id,
    licenseKey: row.license_key,
    customerUsername: row.customer_username,
    deviceId: row.device_id,
    deviceName: row.device_name,
    instanceId: row.instance_id,
    instanceHash: row.instance_hash,
    instanceName: row.instance_name || row.registered_instance_name || "Unknown Instance",
    serverName: row.server_name,
    pluginVersion: row.plugin_version,
    ipAddress: row.ip_address,
    heartbeatIntervalSeconds: row.heartbeat_interval_seconds,
    startedAt: row.started_at,
    lastHeartbeatAt: row.last_heartbeat_at,
    closedAt: row.closed_at,
    closeReason: row.close_reason,
    staleClosed: Boolean(row.stale_closed),
    online: !row.closed_at
  }));
}

function getOverview() {
  reconcileSessions();
  const db = getDatabase();

  return {
    totalLicenses: db.prepare("SELECT COUNT(*) AS count FROM licenses").get().count,
    activeLicenses: db.prepare("SELECT COUNT(*) AS count FROM licenses WHERE active = 1").get().count,
    expiredLicenses: db.prepare("SELECT COUNT(*) AS count FROM licenses WHERE expires_at IS NOT NULL AND expires_at <= ?").get(nowIso()).count,
    activeDevices: db.prepare("SELECT COUNT(*) AS count FROM license_devices WHERE active = 1").get().count,
    activeInstances: db.prepare(`
      SELECT COUNT(DISTINCT instance_id) AS count
        FROM service_sessions
       WHERE closed_at IS NULL
         AND instance_id IS NOT NULL
    `).get().count,
    openSessions: db.prepare("SELECT COUNT(*) AS count FROM service_sessions WHERE closed_at IS NULL").get().count,
    totalAuditEvents: db.prepare("SELECT COUNT(*) AS count FROM audit_logs").get().count
  };
}

function createLicense(input = {}) {
  const db = getDatabase();
  const now = nowIso();
  const username = normalizeIdentifier(input.customerUsername || input.username);
  const displayName = normalizeText(input.displayName || username);
  const notes = normalizeText(input.notes);
  const licenseType = normalizeLicenseType(input.licenseType);
  const sharedMaxSlots = input.maxSlots !== undefined
    ? clampInteger(input.maxSlots, 1, 100, 1)
    : null;
  const maxHwids = sharedMaxSlots ?? clampInteger(input.maxHwids, 1, 100, 1);
  const maxInstances = sharedMaxSlots ?? clampInteger(input.maxInstances, 1, 100, 1);
  const resetIntervalDays = clampInteger(input.resetIntervalDays, 1, 365, config.defaultResetIntervalDays);
  const active = input.active === undefined ? 1 : (input.active ? 1 : 0);

  let expiresAt;
  try {
    expiresAt = resolveLicenseExpiry(licenseType, input.expiresAt, now);
  } catch (error) {
    throw new HttpError(400, error.message);
  }

  let key = normalizeText(input.key).toUpperCase();
  if (!key) {
    do {
      key = generateLicenseKey();
    } while (db.prepare("SELECT 1 FROM licenses WHERE license_key = ?").get(key));
  } else if (db.prepare("SELECT 1 FROM licenses WHERE license_key = ?").get(key)) {
    throw new HttpError(409, "That license key already exists.");
  }

  const result = db.prepare(`
    INSERT INTO licenses (
      license_key,
      customer_username,
      display_name,
      license_type,
      expires_at,
      max_hwids,
      max_instances,
      active,
      notes,
      reset_interval_days,
      next_reset_at,
      created_at,
      updated_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
  `).run(
    key,
    username,
    displayName,
    licenseType,
    expiresAt,
    maxHwids,
    maxInstances,
    active,
    notes,
    resetIntervalDays,
    now,
    now
  );

  logAudit({
    licenseId: result.lastInsertRowid,
    actor: "admin",
    action: "license_created",
    details: {
      key,
      username,
      licenseType,
      expiresAt,
      maxHwids,
      maxInstances,
      resetIntervalDays
    }
  });

  return serializeLicense(getLicenseById(result.lastInsertRowid));
}

function updateLicense(licenseId, updates = {}) {
  const db = getDatabase();
  const existing = getLicenseById(licenseId);
  if (!existing) {
    throw new HttpError(404, "License not found.");
  }

  const currentLicenseType = normalizeLicenseType(existing.license_type);
  const nextLicenseType = updates.licenseType !== undefined
    ? normalizeLicenseType(updates.licenseType, currentLicenseType)
    : currentLicenseType;

  const sharedMaxSlots = updates.maxSlots !== undefined
    ? clampInteger(updates.maxSlots, 1, 100, Math.max(existing.max_hwids, existing.max_instances))
    : null;
  const maxHwids = sharedMaxSlots ?? (updates.maxHwids !== undefined
    ? clampInteger(updates.maxHwids, 1, 100, existing.max_hwids)
    : existing.max_hwids);
  const maxInstances = sharedMaxSlots ?? (updates.maxInstances !== undefined
    ? clampInteger(updates.maxInstances, 1, 100, existing.max_instances)
    : existing.max_instances);
  const active = updates.active !== undefined ? (updates.active ? 1 : 0) : existing.active;
  const resetIntervalDays = updates.resetIntervalDays !== undefined
    ? clampInteger(updates.resetIntervalDays, 1, 365, existing.reset_interval_days)
    : existing.reset_interval_days;
  const customerUsername = updates.customerUsername !== undefined
    ? normalizeIdentifier(updates.customerUsername)
    : existing.customer_username;
  const displayName = updates.displayName !== undefined
    ? normalizeText(updates.displayName)
    : existing.display_name;
  const notes = updates.notes !== undefined
    ? normalizeText(updates.notes)
    : existing.notes;

  let expiresAt = existing.expires_at;
  try {
    if (updates.expiresAt !== undefined) {
      expiresAt = resolveLicenseExpiry(nextLicenseType, updates.expiresAt, nowIso());
    } else if (updates.licenseType !== undefined && nextLicenseType !== currentLicenseType) {
      expiresAt = resolveLicenseExpiry(nextLicenseType, undefined, nowIso());
    }
  } catch (error) {
    throw new HttpError(400, error.message);
  }

  db.prepare(`
    UPDATE licenses
       SET customer_username = ?,
           display_name = ?,
           license_type = ?,
           expires_at = ?,
           max_hwids = ?,
           max_instances = ?,
           active = ?,
           notes = ?,
           reset_interval_days = ?,
           updated_at = ?
     WHERE id = ?
  `).run(
    customerUsername,
    displayName,
    nextLicenseType,
    expiresAt,
    maxHwids,
    maxInstances,
    active,
    notes,
    resetIntervalDays,
    nowIso(),
    licenseId
  );

  logAudit({
    licenseId,
    actor: "admin",
    action: "license_updated",
    details: {
      licenseType: nextLicenseType,
      expiresAt,
      maxHwids,
      maxInstances,
      active,
      resetIntervalDays,
      customerUsername,
      displayName
    }
  });

  return serializeLicense(getLicenseById(licenseId));
}

function extendLicenseTerm(licenseId) {
  const db = getDatabase();
  const existing = getLicenseById(licenseId);
  if (!existing) {
    throw new HttpError(404, "License not found.");
  }

  const licenseType = normalizeLicenseType(existing.license_type);
  if (licenseType === "lifetime") {
    throw new HttpError(400, "Lifetime licenses do not need time extensions.");
  }

  const baseExpiry = existing.expires_at && !isLicenseExpired(existing.expires_at)
    ? existing.expires_at
    : nowIso();
  const expiresAt = licenseType === "yearly"
    ? endOfLocalDay(addCalendarYears(baseExpiry, 1))
    : endOfLocalDay(addCalendarMonths(baseExpiry, 1));
  const updatedAt = nowIso();

  db.prepare(`
    UPDATE licenses
       SET expires_at = ?,
           updated_at = ?
     WHERE id = ?
  `).run(expiresAt, updatedAt, licenseId);

  logAudit({
    licenseId,
    actor: "admin",
    action: "license_extended",
    details: {
      licenseType,
      previousExpiresAt: existing.expires_at || null,
      newExpiresAt: expiresAt
    }
  });

  return serializeLicense(getLicenseById(licenseId));
}

function deleteLicense(licenseId) {
  const db = getDatabase();
  const existing = getLicenseById(licenseId);
  if (!existing) {
    throw new HttpError(404, "License not found.");
  }

  const licenseSnapshot = {
    id: existing.id,
    key: existing.license_key,
    customerUsername: existing.customer_username,
    displayName: existing.display_name,
    licenseType: normalizeLicenseType(existing.license_type)
  };

  db.prepare(`
    DELETE FROM licenses
     WHERE id = ?
  `).run(licenseId);

  logAudit({
    actor: "admin",
    action: "license_deleted",
    details: licenseSnapshot
  });

  return {
    ok: true,
    deleted: licenseSnapshot
  };
}

function activateLicense(payload = {}) {
  reconcileSessions();

  const db = getDatabase();
  const license = getLicenseByKey(payload.licenseKey);
  assertLicenseForActivation(license);

  const username = normalizeIdentifier(payload.username);
  if (!username) {
    throw new HttpError(400, "A username is required.");
  }

  if (license.customer_username) {
    if (normalizeIdentifier(license.customer_username) !== username) {
      throw new HttpError(403, "The provided username does not match this license.");
    }
  } else {
    db.prepare("UPDATE licenses SET customer_username = ?, updated_at = ? WHERE id = ?").run(username, nowIso(), license.id);
  }

  const heartbeatIntervalSeconds = clampInteger(payload.heartbeatIntervalSeconds, 10, 300, config.defaultHeartbeatSeconds);
  const serverInfo = sanitizeServerInfo(payload.server);
  const fingerprint = buildHwidFingerprint({
    ...(payload.machine || {}),
    pcName: payload.pcName || payload.machine?.pcName || payload.machine?.hostName
  });
  const instanceFingerprint = buildInstanceFingerprint(payload.instance || {}, serverInfo);

  if (!instanceFingerprint.normalized.instanceUuid) {
    throw new HttpError(400, "A stable instance UUID is required.");
  }
  if (!instanceFingerprint.normalized.worldContainerHash && !instanceFingerprint.normalized.pluginsPathHash && !instanceFingerprint.normalized.serverRootHash) {
    throw new HttpError(400, "Stable instance path anchors are required.");
  }

  const deviceName = normalizeText(payload.pcName || payload.machine?.pcName || fingerprint.normalized.pcName, "Unknown Device");
  const instanceName = normalizeText(payload.instance?.instanceName || payload.instance?.name || serverInfo.serverName, "Unknown Instance");
  const timestamp = nowIso();
  const deviceFingerprintJson = JSON.stringify({
    normalized: fingerprint.normalized,
    hwidHash: fingerprint.hash,
    server: serverInfo
  });
  const instanceFingerprintJson = JSON.stringify({
    normalized: instanceFingerprint.normalized,
    instanceHash: instanceFingerprint.hash,
    server: serverInfo
  });

  const transaction = db.transaction(() => {
    let device = db.prepare(`
      SELECT *
        FROM license_devices
       WHERE license_id = ?
         AND hwid_hash = ?
    `).get(license.id, fingerprint.hash);

    const activeDeviceCount = db.prepare(`
      SELECT COUNT(*) AS count
        FROM license_devices
       WHERE license_id = ?
         AND active = 1
    `).get(license.id).count;

    if (!device && activeDeviceCount >= license.max_hwids) {
      throw new HttpError(403, "This license has reached its HWID limit.", {
        maxHwids: license.max_hwids,
        activeDeviceCount
      });
    }

    if (device) {
      db.prepare(`
        UPDATE license_devices
           SET device_name = ?,
               fingerprint_json = ?,
               last_seen_at = ?,
               last_username = ?,
               active = 1,
               reset_at = NULL
         WHERE id = ?
      `).run(deviceName, deviceFingerprintJson, timestamp, username, device.id);
    } else {
      const insert = db.prepare(`
        INSERT INTO license_devices (
          license_id,
          hwid_hash,
          device_name,
          fingerprint_json,
          first_seen_at,
          last_seen_at,
          last_username,
          active
        ) VALUES (?, ?, ?, ?, ?, ?, ?, 1)
      `).run(license.id, fingerprint.hash, deviceName, deviceFingerprintJson, timestamp, timestamp, username);
      device = db.prepare("SELECT * FROM license_devices WHERE id = ?").get(insert.lastInsertRowid);
    }

    let instance = db.prepare(`
      SELECT *
        FROM license_instances
       WHERE license_id = ?
         AND device_id = ?
         AND instance_hash = ?
    `).get(license.id, device.id, instanceFingerprint.hash);

    const activeInstanceCount = db.prepare(`
      SELECT COUNT(*) AS count
        FROM license_instances
       WHERE license_id = ?
         AND active = 1
    `).get(license.id).count;

    if (!instance && activeInstanceCount >= license.max_instances) {
      throw new HttpError(403, "This license has reached its instance limit.", {
        maxInstances: license.max_instances,
        activeInstanceCount
      });
    }

    if (instance) {
      db.prepare(`
        UPDATE license_instances
           SET instance_uuid = ?,
               instance_name = ?,
               fingerprint_json = ?,
               last_seen_at = ?,
               last_server_name = ?,
               active = 1,
               reset_at = NULL
         WHERE id = ?
      `).run(
        instanceFingerprint.normalized.instanceUuid,
        instanceName,
        instanceFingerprintJson,
        timestamp,
        serverInfo.serverName,
        instance.id
      );
    } else {
      const insert = db.prepare(`
        INSERT INTO license_instances (
          license_id,
          device_id,
          instance_hash,
          instance_uuid,
          instance_name,
          fingerprint_json,
          first_seen_at,
          last_seen_at,
          last_server_name,
          active
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
      `).run(
        license.id,
        device.id,
        instanceFingerprint.hash,
        instanceFingerprint.normalized.instanceUuid,
        instanceName,
        instanceFingerprintJson,
        timestamp,
        timestamp,
        serverInfo.serverName
      );
      instance = db.prepare("SELECT * FROM license_instances WHERE id = ?").get(insert.lastInsertRowid);
    }

    closeOpenSessionsForInstance(instance.id, "reauth", timestamp);

    const session = createSession({
      licenseId: license.id,
      deviceId: device.id,
      instanceId: instance.id,
      instanceHash: instanceFingerprint.hash,
      instanceName,
      serverName: serverInfo.serverName,
      pluginVersion: serverInfo.pluginVersion,
      ipAddress: normalizeText(payload.ipAddress),
      heartbeatIntervalSeconds
    });

    db.prepare("UPDATE licenses SET updated_at = ? WHERE id = ?").run(timestamp, license.id);

    logAudit({
      licenseId: license.id,
      deviceId: device.id,
      actor: "plugin",
      action: "license_activated",
      details: {
        username,
        deviceName,
        instanceName,
        heartbeatIntervalSeconds,
        serverName: serverInfo.serverName,
        pluginVersion: serverInfo.pluginVersion
      }
    });

    const refreshedLicense = getLicenseById(license.id);
    return { session, device, instance, refreshedLicense };
  });

  const result = transaction();
  return {
    ok: true,
    message: "License authenticated.",
    sessionToken: result.session.token,
    heartbeatIntervalSeconds,
    deviceId: result.device.id,
    instanceId: result.instance.id,
    hwidHash: fingerprint.hash,
    instanceHash: instanceFingerprint.hash,
    maxHwids: result.refreshedLicense.max_hwids,
    maxInstances: result.refreshedLicense.max_instances,
    activeDeviceCount: result.refreshedLicense.active_device_count,
    activeInstanceCount: result.refreshedLicense.active_instance_count,
    expiresAt: result.refreshedLicense.expires_at || null
  };
}

function heartbeatLicenseSession(payload = {}) {
  reconcileSessions();

  const sessionToken = normalizeText(payload.sessionToken);
  if (!sessionToken) {
    throw new HttpError(400, "A session token is required.");
  }

  const session = touchSession(sessionToken, {
    serverName: normalizeText(payload.serverName),
    pluginVersion: normalizeText(payload.pluginVersion),
    ipAddress: normalizeText(payload.ipAddress),
    instanceName: normalizeText(payload.instanceName)
  });

  if (!session) {
    throw new HttpError(404, "Session not found or already closed.");
  }

  const license = getLicenseById(session.license_id);
  if (!license || !license.active || isLicenseExpired(license.expires_at)) {
    closeSession(sessionToken, isLicenseExpired(license?.expires_at) ? "license_expired" : "license_disabled");
    throw new HttpError(403, !license ? "License no longer exists." : (isLicenseExpired(license.expires_at) ? "This license has expired." : "This license is disabled."));
  }

  const db = getDatabase();
  const timestamp = nowIso();
  db.prepare(`
    UPDATE license_devices
       SET last_seen_at = ?
     WHERE id = ?
  `).run(timestamp, session.device_id);
  db.prepare(`
    UPDATE license_instances
       SET last_seen_at = ?,
           last_server_name = COALESCE(NULLIF(?, ''), last_server_name)
     WHERE id = ?
  `).run(timestamp, normalizeText(payload.serverName), session.instance_id);

  return {
    ok: true,
    message: "Heartbeat accepted.",
    licenseId: session.license_id,
    deviceId: session.device_id,
    instanceId: session.instance_id,
    sessionToken
  };
}

function shutdownLicenseSession(payload = {}) {
  const sessionToken = normalizeText(payload.sessionToken);
  if (!sessionToken) {
    throw new HttpError(400, "A session token is required.");
  }

  const existing = getSessionByToken(sessionToken);
  if (!existing) {
    return { ok: true, message: "Session already gone." };
  }

  closeSession(sessionToken, normalizeText(payload.reason, "shutdown"));
  logAudit({
    licenseId: existing.license_id,
    deviceId: existing.device_id,
    actor: "plugin",
    action: "license_shutdown",
    details: {
      reason: normalizeText(payload.reason, "shutdown"),
      instanceId: existing.instance_id,
      instanceName: existing.instance_name
    }
  });

  return { ok: true, message: "Shutdown stored." };
}

function lookupResetState({ licenseKey, username, ipAddress } = {}) {
  if (ipAddress !== undefined && ipAddress !== null) {
    claimResetLookupAttempt(ipAddress);
  }
  reconcileSessions();

  const license = getSelfServiceResetLicense(licenseKey, username);

  const devices = listLicenseDevices(license.id).filter((device) => device.active);
  const nextResetAt = license.next_reset_at || null;
  const nextResetTimestamp = nextResetAt ? Date.parse(nextResetAt) : 0;
  const cooldownRemainingMs = nextResetTimestamp && nextResetTimestamp > Date.now()
    ? nextResetTimestamp - Date.now()
    : 0;

  return {
    ok: true,
    license: serializeLicense(getLicenseById(license.id)),
    devices,
    cooldownRemainingMs
  };
}

function resetLicenseDevices(license, deviceIds = [], options = {}) {
  const db = getDatabase();
  if (!license) {
    throw new HttpError(404, "Unknown license key.");
  }

  const ignoreCooldown = Boolean(options.ignoreCooldown);
  const actor = normalizeText(options.actor, "self_service");
  const closeReason = normalizeText(options.closeReason, actor === "admin" ? "admin_reset" : "user_reset");
  const updateCooldown = options.updateCooldown === undefined ? !ignoreCooldown : Boolean(options.updateCooldown);

  const nextResetTimestamp = license.next_reset_at ? Date.parse(license.next_reset_at) : 0;
  if (!ignoreCooldown && nextResetTimestamp && nextResetTimestamp > Date.now()) {
    throw new HttpError(429, "HWID reset cooldown is still active.", {
      nextResetAt: license.next_reset_at
    });
  }

  const normalizedIds = [...new Set((Array.isArray(deviceIds) ? deviceIds : []).map((id) => Number.parseInt(id, 10)).filter(Number.isFinite))];
  if (normalizedIds.length === 0) {
    throw new HttpError(400, "Select at least one registered device.");
  }

  const placeholders = normalizedIds.map(() => "?").join(",");
  const devices = db.prepare(`
    SELECT *
      FROM license_devices
     WHERE license_id = ?
       AND active = 1
       AND id IN (${placeholders})
  `).all(license.id, ...normalizedIds);

  if (devices.length === 0) {
    throw new HttpError(404, "No matching active devices were found.");
  }

  const timestamp = nowIso();
  const nextResetAt = addDays(timestamp, clampInteger(license.reset_interval_days, 1, 365, config.defaultResetIntervalDays));

  const transaction = db.transaction(() => {
    db.prepare(`
      UPDATE license_devices
         SET active = 0,
             reset_at = ?
       WHERE license_id = ?
         AND id IN (${placeholders})
    `).run(timestamp, license.id, ...normalizedIds);

    db.prepare(`
      UPDATE license_instances
         SET active = 0,
             reset_at = ?
       WHERE license_id = ?
         AND device_id IN (${placeholders})
    `).run(timestamp, license.id, ...normalizedIds);

    db.prepare(`
      UPDATE service_sessions
         SET closed_at = ?,
             close_reason = ?,
             stale_closed = 0
       WHERE license_id = ?
         AND device_id IN (${placeholders})
         AND closed_at IS NULL
    `).run(timestamp, closeReason, license.id, ...normalizedIds);

    if (updateCooldown) {
      db.prepare(`
        UPDATE licenses
           SET next_reset_at = ?,
               updated_at = ?
         WHERE id = ?
      `).run(nextResetAt, timestamp, license.id);
    } else {
      db.prepare(`
        UPDATE licenses
           SET updated_at = ?
         WHERE id = ?
      `).run(timestamp, license.id);
    }
  });

  transaction();

  for (const device of devices) {
    logAudit({
      licenseId: license.id,
      deviceId: device.id,
      actor,
      action: "device_reset",
      details: { deviceName: device.device_name }
    });
  }

  return lookupResetState({
    licenseKey: license.license_key,
    username: license.customer_username,
    ipAddress: null
  });
}

function resetLicenseInstances(license, instanceIds = [], options = {}) {
  const db = getDatabase();
  if (!license) {
    throw new HttpError(404, "License not found.");
  }

  const actor = normalizeText(options.actor, "admin");
  const closeReason = normalizeText(options.closeReason, "admin_instance_reset");
  const normalizedIds = [...new Set((Array.isArray(instanceIds) ? instanceIds : []).map((id) => Number.parseInt(id, 10)).filter(Number.isFinite))];
  if (normalizedIds.length === 0) {
    throw new HttpError(400, "Select at least one registered instance.");
  }

  const placeholders = normalizedIds.map(() => "?").join(",");
  const instances = db.prepare(`
    SELECT *
      FROM license_instances
     WHERE license_id = ?
       AND active = 1
       AND id IN (${placeholders})
  `).all(license.id, ...normalizedIds);

  if (instances.length === 0) {
    throw new HttpError(404, "No matching active instances were found.");
  }

  const timestamp = nowIso();
  db.transaction(() => {
    db.prepare(`
      UPDATE license_instances
         SET active = 0,
             reset_at = ?
       WHERE license_id = ?
         AND id IN (${placeholders})
    `).run(timestamp, license.id, ...normalizedIds);

    db.prepare(`
      UPDATE service_sessions
         SET closed_at = ?,
             close_reason = ?,
             stale_closed = 0
       WHERE license_id = ?
         AND instance_id IN (${placeholders})
         AND closed_at IS NULL
    `).run(timestamp, closeReason, license.id, ...normalizedIds);

    db.prepare(`
      UPDATE licenses
         SET updated_at = ?
       WHERE id = ?
    `).run(timestamp, license.id);
  })();

  for (const instance of instances) {
    logAudit({
      licenseId: license.id,
      deviceId: instance.device_id,
      actor,
      action: "instance_reset",
      details: { instanceName: instance.instance_name, instanceId: instance.id }
    });
  }

  return {
    ok: true,
    license: serializeLicense(getLicenseById(license.id)),
    instances: listLicenseInstances(license.id)
  };
}

function resetLicenseDevicesByKey(licenseKey, username, deviceIds = []) {
  const license = getSelfServiceResetLicense(licenseKey, username);
  return resetLicenseDevices(license, deviceIds, {
    actor: "self_service",
    closeReason: "user_reset",
    ignoreCooldown: false,
    updateCooldown: true
  });
}

function adminResetDevices(licenseId, deviceIds = []) {
  const license = getLicenseById(licenseId);
  if (!license) {
    throw new HttpError(404, "License not found.");
  }

  const result = resetLicenseDevices(license, deviceIds, {
    actor: "admin",
    closeReason: "admin_reset",
    ignoreCooldown: true,
    updateCooldown: false
  });
  logAudit({
    licenseId,
    actor: "admin",
    action: "admin_device_reset",
    details: { deviceIds }
  });
  return result;
}

function adminResetInstances(licenseId, instanceIds = []) {
  const license = getLicenseById(licenseId);
  if (!license) {
    throw new HttpError(404, "License not found.");
  }

  const result = resetLicenseInstances(license, instanceIds, {
    actor: "admin",
    closeReason: "admin_instance_reset"
  });
  logAudit({
    licenseId,
    actor: "admin",
    action: "admin_instance_reset",
    details: { instanceIds }
  });
  return result;
}

module.exports = {
  activateLicense,
  adminResetDevices,
  adminResetInstances,
  createLicense,
  deleteLicense,
  extendLicenseTerm,
  getOverview,
  heartbeatLicenseSession,
  listLicenseDevices,
  listLicenseInstances,
  listLicenses,
  listRecentSessions,
  lookupResetState,
  resetLicenseDevicesByKey,
  shutdownLicenseSession,
  updateLicense
};
