const http = require("http");
const https = require("https");
const { URL } = require("url");
const { config } = require("./config");
const { getDatabase } = require("./database");
const { normalizeText, nowIso, safeJsonParse } = require("./utils");

const AUTH_FAILURE_WINDOW_MS = 15 * 60 * 1000;
const AUTH_FAILURE_THRESHOLD = 5;
const RESET_ACTIVITY_WINDOW_MS = 24 * 60 * 60 * 1000;
const RESET_ACTIVITY_THRESHOLD = 3;
const EXPIRY_WARNING_DAYS = 7;

const WEBHOOK_EVENT_DEFINITIONS = Object.freeze([
  {
    id: "expiringSoon",
    label: "License expiring soon",
    description: "Send an alert when the license is within 7 days of expiry."
  },
  {
    id: "newDevice",
    label: "New device registered",
    description: "Send an alert when a new HWID is registered on the license."
  },
  {
    id: "instanceLimitDenied",
    label: "Instance limit denied",
    description: "Send an alert when an instance reconnect or startup is denied by the instance cap."
  },
  {
    id: "repeatedAuthFailures",
    label: "Repeated auth failures",
    description: "Send an alert when repeated license auth failures happen in a short time window."
  },
  {
    id: "unusualResetActivity",
    label: "Unusual reset activity",
    description: "Send an alert when multiple reset actions happen in a short period."
  }
]);

const DEFAULT_WEBHOOK_EVENTS = Object.freeze(
  WEBHOOK_EVENT_DEFINITIONS.reduce((accumulator, definition) => {
    accumulator[definition.id] = true;
    return accumulator;
  }, {})
);

const AUTH_FAILURE_ACTIONS = new Set([
  "auth_denied_unknown_license",
  "auth_denied_username_mismatch",
  "auth_denied_license_disabled",
  "auth_denied_license_expired",
  "auth_denied_device_blacklisted",
  "auth_denied_instance_blacklisted",
  "hwid_limit_denied",
  "instance_limit_denied",
  "heartbeat_denied_session_revoked",
  "heartbeat_denied_license_missing",
  "heartbeat_denied_license_disabled",
  "heartbeat_denied_license_expired"
]);

const AUTH_ATTEMPT_ACTIONS = Object.freeze([
  "license_activated",
  ...AUTH_FAILURE_ACTIONS
]);

const RESET_ACTIVITY_ACTIONS = new Set([
  "device_reset",
  "instance_reset",
  "admin_device_reset",
  "admin_instance_reset"
]);

const WEBHOOK_COLORS = Object.freeze({
  expiringSoon: 0xf5c451,
  newDevice: 0x52d38c,
  instanceLimitDenied: 0xff8aa8,
  repeatedAuthFailures: 0xffb347,
  unusualResetActivity: 0xff8aa8
});

function getWebhookEventDefinitions() {
  return WEBHOOK_EVENT_DEFINITIONS.map((definition) => ({ ...definition }));
}

function normalizeWebhookEventSettings(input = {}) {
  const source = typeof input === "string" ? safeJsonParse(input, {}) : (input || {});
  const normalized = {};
  for (const definition of WEBHOOK_EVENT_DEFINITIONS) {
    normalized[definition.id] = source[definition.id] === undefined
      ? DEFAULT_WEBHOOK_EVENTS[definition.id]
      : Boolean(source[definition.id]);
  }
  return normalized;
}

function normalizeDiscordWebhookUrl(value) {
  const urlValue = normalizeText(value);
  if (!urlValue) {
    return "";
  }

  let parsed;
  try {
    parsed = new URL(urlValue);
  } catch (_error) {
    throw new Error("Enter a valid Discord webhook URL.");
  }

  const host = parsed.hostname.toLowerCase();
  const pathName = parsed.pathname.toLowerCase();
  const isDiscordHost = host === "discord.com" || host === "discordapp.com" || host === "ptb.discord.com" || host === "canary.discord.com";
  if (parsed.protocol !== "https:" || !isDiscordHost || !pathName.startsWith("/api/webhooks/")) {
    throw new Error("Only Discord webhook URLs are supported.");
  }

  return parsed.toString();
}

function getLicenseWebhookConfig(licenseId) {
  if (!Number.isFinite(Number(licenseId))) {
    return {
      url: "",
      events: normalizeWebhookEventSettings({}),
      configured: false
    };
  }

  const row = getDatabase().prepare(`
    SELECT license_key,
           customer_username,
           display_name,
           expires_at,
           webhook_url,
           webhook_events_json
      FROM licenses
     WHERE id = ?
  `).get(licenseId);

  if (!row) {
    return {
      url: "",
      events: normalizeWebhookEventSettings({}),
      configured: false
    };
  }

  return {
    url: normalizeText(row.webhook_url),
    events: normalizeWebhookEventSettings(row.webhook_events_json),
    configured: Boolean(normalizeText(row.webhook_url)),
    licenseKey: row.license_key,
    customerUsername: row.customer_username,
    displayName: row.display_name,
    expiresAt: row.expires_at || null
  };
}

function buildDiscordPayload({ eventKey, title, description, licenseContext, fields = [] }) {
  const licenseName = licenseContext?.displayName || licenseContext?.licenseKey || `license #${licenseContext?.licenseId || "unknown"}`;
  const embedFields = [
    {
      name: "License",
      value: String(licenseName),
      inline: true
    },
    {
      name: "User",
      value: String(licenseContext?.customerUsername || "Unbound"),
      inline: true
    },
    ...fields
      .filter((field) => normalizeText(field?.value))
      .map((field) => ({
        name: normalizeText(field.name, "Field").slice(0, 256),
        value: normalizeText(field.value, "-").slice(0, 1024),
        inline: Boolean(field.inline)
      }))
  ];

  return {
    username: "Nova AntiCheat",
    allowed_mentions: { parse: [] },
    embeds: [
      {
        title: normalizeText(title, "Nova AntiCheat notification").slice(0, 256),
        description: normalizeText(description, "").slice(0, 4096),
        color: WEBHOOK_COLORS[eventKey] || 0x8b5cf6,
        timestamp: nowIso(),
        fields: embedFields.slice(0, 25)
      }
    ]
  };
}

function postJsonToWebhook(urlValue, payload) {
  const parsed = new URL(urlValue);
  const transport = parsed.protocol === "https:" ? https : http;

  return new Promise((resolve, reject) => {
    const request = transport.request({
      protocol: parsed.protocol,
      hostname: parsed.hostname,
      port: parsed.port || undefined,
      path: `${parsed.pathname}${parsed.search}`,
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Content-Length": Buffer.byteLength(payload, "utf8")
      },
      timeout: 10_000
    }, (response) => {
      let body = "";
      response.setEncoding("utf8");
      response.on("data", (chunk) => {
        body += chunk;
      });
      response.on("end", () => {
        resolve({
          statusCode: response.statusCode || 0,
          body
        });
      });
    });

    request.on("timeout", () => {
      request.destroy(new Error("Webhook request timed out."));
    });
    request.on("error", reject);
    request.write(payload);
    request.end();
  });
}

function createWebhookNotificationRecord({ licenseId, eventKey, dedupeKey, payloadJson }) {
  const db = getDatabase();
  try {
    const result = db.prepare(`
      INSERT INTO webhook_notifications (
        license_id,
        event_key,
        dedupe_key,
        status,
        payload_json,
        created_at
      ) VALUES (?, ?, ?, 'queued', ?, ?)
    `).run(licenseId, eventKey, dedupeKey, payloadJson, nowIso());
    return result.lastInsertRowid;
  } catch (error) {
    if (String(error?.code || "").includes("SQLITE_CONSTRAINT")) {
      return null;
    }
    throw error;
  }
}

function updateWebhookNotificationStatus(notificationId, { status, responseStatus = null, responseBody = "", delivered = false }) {
  getDatabase().prepare(`
    UPDATE webhook_notifications
       SET status = ?,
           response_status = ?,
           response_body = ?,
           delivered_at = ?
     WHERE id = ?
  `).run(status, responseStatus, normalizeText(responseBody).slice(0, 4000), delivered ? nowIso() : null, notificationId);
}

async function emitLicenseWebhookAlert({
  licenseId,
  eventKey,
  dedupeKey,
  title,
  description,
  fields = []
}) {
  if (!Number.isFinite(Number(licenseId))) {
    return false;
  }

  const licenseContext = getLicenseWebhookConfig(licenseId);
  if (!licenseContext.configured || !licenseContext.url || !licenseContext.events[eventKey]) {
    return false;
  }

  const payload = buildDiscordPayload({
    eventKey,
    title,
    description,
    licenseContext: {
      ...licenseContext,
      licenseId
    },
    fields
  });
  const payloadJson = JSON.stringify(payload);
  const notificationId = createWebhookNotificationRecord({
    licenseId,
    eventKey,
    dedupeKey: normalizeText(dedupeKey, `${eventKey}:${licenseId}:${Date.now()}`),
    payloadJson
  });

  if (notificationId == null) {
    return false;
  }

  try {
    const response = await postJsonToWebhook(licenseContext.url, payloadJson);
    const success = response.statusCode >= 200 && response.statusCode < 300;
    updateWebhookNotificationStatus(notificationId, {
      status: success ? "delivered" : "failed",
      responseStatus: response.statusCode,
      responseBody: response.body,
      delivered: success
    });
    return success;
  } catch (error) {
    updateWebhookNotificationStatus(notificationId, {
      status: "failed",
      responseBody: error?.message || "Webhook request failed."
    });
    console.error("[NovaAC Website] Discord webhook delivery failed:", error);
    return false;
  }
}

function countRecentAuditActions(licenseId, actions, windowMs) {
  const actionList = [...actions];
  if (!Number.isFinite(Number(licenseId)) || actionList.length === 0) {
    return 0;
  }

  const placeholders = actionList.map(() => "?").join(",");
  const sinceIso = new Date(Date.now() - windowMs).toISOString();
  return getDatabase().prepare(`
    SELECT COUNT(*) AS count
      FROM audit_logs
     WHERE license_id = ?
       AND action IN (${placeholders})
       AND created_at >= ?
  `).get(licenseId, ...actionList, sinceIso).count;
}

function getLatestAuditAction(licenseId, actions) {
  const actionList = [...actions];
  if (!Number.isFinite(Number(licenseId)) || actionList.length === 0) {
    return null;
  }

  const placeholders = actionList.map(() => "?").join(",");
  return getDatabase().prepare(`
    SELECT action, details_json, created_at
      FROM audit_logs
     WHERE license_id = ?
       AND action IN (${placeholders})
     ORDER BY created_at DESC
     LIMIT 1
  `).get(licenseId, ...actionList);
}

async function maybeEmitRepeatedAuthFailureAlert(licenseId) {
  const count = countRecentAuditActions(licenseId, AUTH_FAILURE_ACTIONS, AUTH_FAILURE_WINDOW_MS);
  if (count < AUTH_FAILURE_THRESHOLD) {
    return false;
  }

  const latest = getLatestAuditAction(licenseId, AUTH_FAILURE_ACTIONS);
  const bucket = Math.floor(Date.now() / AUTH_FAILURE_WINDOW_MS);
  return emitLicenseWebhookAlert({
    licenseId,
    eventKey: "repeatedAuthFailures",
    dedupeKey: `repeated-auth-failures:${licenseId}:${bucket}`,
    title: "Repeated auth failures",
    description: `${count} auth failures were recorded in the last 15 minutes.`,
    fields: [
      {
        name: "Latest action",
        value: latest?.action || "unknown",
        inline: true
      },
      {
        name: "Latest event",
        value: latest?.created_at || "-",
        inline: true
      }
    ]
  });
}

async function maybeEmitUnusualResetActivityAlert(licenseId) {
  const count = countRecentAuditActions(licenseId, RESET_ACTIVITY_ACTIONS, RESET_ACTIVITY_WINDOW_MS);
  if (count < RESET_ACTIVITY_THRESHOLD) {
    return false;
  }

  const bucket = Math.floor(Date.now() / RESET_ACTIVITY_WINDOW_MS);
  return emitLicenseWebhookAlert({
    licenseId,
    eventKey: "unusualResetActivity",
    dedupeKey: `unusual-reset-activity:${licenseId}:${bucket}`,
    title: "Unusual reset activity",
    description: `${count} reset actions were recorded in the last 24 hours.`,
    fields: [
      {
        name: "Window",
        value: "24 hours",
        inline: true
      },
      {
        name: "Reset count",
        value: String(count),
        inline: true
      }
    ]
  });
}

async function handleAuditNotification(entry) {
  if (!entry?.licenseId) {
    return;
  }

  if (entry.action === "device_registered") {
    await emitLicenseWebhookAlert({
      licenseId: entry.licenseId,
      eventKey: "newDevice",
      dedupeKey: `new-device:${entry.licenseId}:${entry.auditId}`,
      title: "New device registered",
      description: `${entry.details?.deviceName || "A new device"} was added to the license.`,
      fields: [
        {
          name: "Device",
          value: entry.details?.deviceName || "Unknown device",
          inline: true
        },
        {
          name: "HWID",
          value: entry.details?.hwidHash || "-",
          inline: false
        }
      ]
    });
    return;
  }

  if (entry.action === "instance_limit_denied") {
    await emitLicenseWebhookAlert({
      licenseId: entry.licenseId,
      eventKey: "instanceLimitDenied",
      dedupeKey: `instance-limit-denied:${entry.licenseId}:${entry.auditId}`,
      title: "Instance limit denied",
      description: "An instance tried to authenticate but the instance cap was already full.",
      fields: [
        {
          name: "Instance",
          value: entry.details?.instanceName || "Unknown instance",
          inline: true
        },
        {
          name: "Current count",
          value: `${entry.details?.activeInstanceCount ?? "?"}/${entry.details?.maxInstances ?? "?"}`,
          inline: true
        }
      ]
    });
  }

  if (AUTH_FAILURE_ACTIONS.has(entry.action)) {
    await maybeEmitRepeatedAuthFailureAlert(entry.licenseId);
  }

  if (RESET_ACTIVITY_ACTIONS.has(entry.action)) {
    await maybeEmitUnusualResetActivityAlert(entry.licenseId);
  }
}

function scheduleAuditNotification(entry) {
  setImmediate(() => {
    handleAuditNotification(entry).catch((error) => {
      console.error("[NovaAC Website] Audit notification processing failed:", error);
    });
  });
}

function normalizeAuditLimit(value, fallback = 0) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed)) {
    return fallback;
  }
  return Math.max(0, parsed);
}

function pruneAuditLogs(database, licenseId = null) {
  const retentionDays = normalizeAuditLimit(config.auditRetentionDays);
  if (retentionDays > 0) {
    const cutoff = new Date(Date.now() - (retentionDays * 24 * 60 * 60 * 1000)).toISOString();
    database.prepare(`
      DELETE FROM audit_logs
       WHERE created_at < ?
    `).run(cutoff);
  }

  const maxRowsPerLicense = normalizeAuditLimit(config.auditMaxRowsPerLicense);
  const normalizedLicenseId = Number.parseInt(licenseId, 10);
  if (maxRowsPerLicense > 0 && Number.isFinite(normalizedLicenseId)) {
    database.prepare(`
      DELETE FROM audit_logs
       WHERE license_id = ?
         AND id NOT IN (
           SELECT id
             FROM audit_logs
            WHERE license_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT ?
         )
    `).run(normalizedLicenseId, normalizedLicenseId, maxRowsPerLicense);
  }

  const maxTotalRows = normalizeAuditLimit(config.auditMaxTotalRows);
  if (maxTotalRows > 0) {
    database.prepare(`
      DELETE FROM audit_logs
       WHERE id NOT IN (
         SELECT id
           FROM audit_logs
          ORDER BY created_at DESC, id DESC
          LIMIT ?
       )
    `).run(maxTotalRows);
  }
}

function logAudit({ licenseId = null, deviceId = null, actor, action, details = {} }) {
  const createdAt = nowIso();
  const detailsJson = JSON.stringify(details || {});
  const database = getDatabase();
  const result = database.prepare(`
    INSERT INTO audit_logs (license_id, device_id, actor, action, details_json, created_at)
    VALUES (?, ?, ?, ?, ?, ?)
  `).run(licenseId, deviceId, actor, action, detailsJson, createdAt);

  try {
    pruneAuditLogs(database, licenseId);
  } catch (error) {
    console.error("[NovaAC Website] Audit log pruning failed:", error);
  }

  scheduleAuditNotification({
    auditId: result.lastInsertRowid,
    licenseId,
    deviceId,
    actor,
    action,
    details,
    createdAt
  });

  return result.lastInsertRowid;
}

function listAuditLogs({ licenseId, actor, action, search, limit = 150 } = {}) {
  const conditions = [];
  const parameters = [];

  const normalizedLicenseId = Number.parseInt(licenseId, 10);
  if (Number.isFinite(normalizedLicenseId)) {
    conditions.push("a.license_id = ?");
    parameters.push(normalizedLicenseId);
  }

  const normalizedActor = normalizeText(actor);
  if (normalizedActor) {
    conditions.push("a.actor = ?");
    parameters.push(normalizedActor);
  }

  const normalizedAction = normalizeText(action);
  if (normalizedAction) {
    conditions.push("a.action = ?");
    parameters.push(normalizedAction);
  }

  const normalizedSearch = normalizeText(search);
  if (normalizedSearch) {
    conditions.push(`(
      lower(a.actor) LIKE ?
      OR lower(a.action) LIKE ?
      OR lower(COALESCE(l.license_key, '')) LIKE ?
      OR lower(COALESCE(l.customer_username, '')) LIKE ?
      OR lower(COALESCE(l.display_name, '')) LIKE ?
      OR lower(a.details_json) LIKE ?
    )`);
    const pattern = `%${normalizedSearch.toLowerCase()}%`;
    parameters.push(pattern, pattern, pattern, pattern, pattern, pattern);
  }

  const whereClause = conditions.length ? `WHERE ${conditions.join(" AND ")}` : "";
  const rows = getDatabase().prepare(`
    SELECT a.*,
           l.license_key,
           l.customer_username,
           l.display_name,
           d.device_name
      FROM audit_logs a
      LEFT JOIN licenses l ON l.id = a.license_id
      LEFT JOIN license_devices d ON d.id = a.device_id
      ${whereClause}
     ORDER BY a.created_at DESC
     LIMIT ?
  `).all(...parameters, Math.min(500, Math.max(10, Number.parseInt(limit, 10) || 150)));

  return rows.map((row) => ({
    id: row.id,
    licenseId: row.license_id,
    licenseKey: row.license_key || "",
    customerUsername: row.customer_username || "",
    displayName: row.display_name || "",
    deviceId: row.device_id,
    deviceName: row.device_name || "",
    actor: row.actor,
    action: row.action,
    details: safeJsonParse(row.details_json, {}),
    createdAt: row.created_at
  }));
}

function listRecentLicenseAuthAttempts(licenseId, limit = 12) {
  const normalizedLicenseId = Number.parseInt(licenseId, 10);
  if (!Number.isFinite(normalizedLicenseId)) {
    return [];
  }

  const placeholders = AUTH_ATTEMPT_ACTIONS.map(() => "?").join(",");
  const rows = getDatabase().prepare(`
    SELECT a.*,
           d.device_name
      FROM audit_logs a
      LEFT JOIN license_devices d ON d.id = a.device_id
     WHERE a.license_id = ?
       AND a.action IN (${placeholders})
     ORDER BY a.created_at DESC
     LIMIT ?
  `).all(
    normalizedLicenseId,
    ...AUTH_ATTEMPT_ACTIONS,
    Math.min(50, Math.max(5, Number.parseInt(limit, 10) || 12))
  );

  return rows.map((row) => {
    const details = safeJsonParse(row.details_json, {});
    const deviceFingerprint = normalizeText(details.hwidHash);
    const instanceUuid = normalizeText(details.instanceUuid);
    const instanceHash = normalizeText(details.instanceHash);

    return {
      id: row.id,
      licenseId: row.license_id,
      deviceId: row.device_id,
      deviceName: row.device_name || "",
      actor: row.actor,
      action: row.action,
      details,
      createdAt: row.created_at,
      canBlockDevice: Boolean(row.device_id || deviceFingerprint),
      canBlockInstance: Boolean(instanceUuid || instanceHash)
    };
  });
}

function getAuditLogFilterOptions() {
  const db = getDatabase();
  return {
    actors: db.prepare(`
      SELECT DISTINCT actor
        FROM audit_logs
       WHERE actor IS NOT NULL
         AND actor != ''
       ORDER BY actor ASC
    `).all().map((row) => row.actor),
    actions: db.prepare(`
      SELECT DISTINCT action
        FROM audit_logs
       WHERE action IS NOT NULL
         AND action != ''
       ORDER BY action ASC
    `).all().map((row) => row.action)
  };
}

async function sendLicenseWebhookTest({ licenseId, webhookUrl } = {}) {
  const normalizedLicenseId = Number.parseInt(licenseId, 10);
  if (!Number.isFinite(normalizedLicenseId)) {
    throw new Error("License not found.");
  }

  const normalizedWebhookUrl = normalizeDiscordWebhookUrl(webhookUrl);
  if (!normalizedWebhookUrl) {
    throw new Error("Enter a Discord webhook URL first.");
  }

  const licenseContext = getLicenseWebhookConfig(normalizedLicenseId);
  if (!licenseContext.licenseKey && !licenseContext.customerUsername) {
    throw new Error("License not found.");
  }

  const payloadJson = JSON.stringify(buildDiscordPayload({
    eventKey: "webhookTest",
    title: "Nova webhook test successful.",
    description: "This Discord webhook is reachable and ready to receive Nova alerts.",
    licenseContext: {
      ...licenseContext,
      licenseId: normalizedLicenseId
    }
  }));

  try {
    const response = await postJsonToWebhook(normalizedWebhookUrl, payloadJson);
    const success = response.statusCode >= 200 && response.statusCode < 300;
    if (!success) {
      throw new Error(response.body
        ? `Discord returned ${response.statusCode}: ${normalizeText(response.body).slice(0, 240)}`
        : `Discord returned ${response.statusCode}.`);
    }

    logAudit({
      licenseId: normalizedLicenseId,
      actor: "self_service",
      action: "license_webhook_test_sent",
      details: {
        responseStatus: response.statusCode
      }
    });

    return {
      ok: true,
      statusCode: response.statusCode
    };
  } catch (error) {
    logAudit({
      licenseId: normalizedLicenseId,
      actor: "self_service",
      action: "license_webhook_test_failed",
      details: {
        message: error?.message || "Webhook test failed."
      }
    });
    throw error;
  }
}

async function processExpiringLicenseNotifications() {
  const now = Date.now();
  const maxExpiry = new Date(now + (EXPIRY_WARNING_DAYS * 86_400_000)).toISOString();
  const rows = getDatabase().prepare(`
    SELECT id,
           license_key,
           customer_username,
           display_name,
           expires_at
      FROM licenses
     WHERE active = 1
       AND webhook_url != ''
       AND expires_at IS NOT NULL
       AND expires_at > ?
       AND expires_at <= ?
  `).all(new Date(now).toISOString(), maxExpiry);

  for (const row of rows) {
    const expiresAtMs = Date.parse(row.expires_at);
    if (!Number.isFinite(expiresAtMs)) {
      continue;
    }

    const daysRemaining = Math.max(1, Math.ceil((expiresAtMs - now) / 86_400_000));
    await emitLicenseWebhookAlert({
      licenseId: row.id,
      eventKey: "expiringSoon",
      dedupeKey: `license-expiring:${row.id}:${row.expires_at}`,
      title: "License expiring soon",
      description: `${row.display_name || row.license_key} expires in ${daysRemaining} day${daysRemaining === 1 ? "" : "s"}.`,
      fields: [
        {
          name: "Expires at",
          value: row.expires_at,
          inline: true
        },
        {
          name: "Days remaining",
          value: String(daysRemaining),
          inline: true
        }
      ]
    });
  }
}

module.exports = {
  getAuditLogFilterOptions,
  getLicenseWebhookConfig,
  getWebhookEventDefinitions,
  listAuditLogs,
  listRecentLicenseAuthAttempts,
  logAudit,
  normalizeDiscordWebhookUrl,
  normalizeWebhookEventSettings,
  pruneAuditLogs,
  sendLicenseWebhookTest,
  processExpiringLicenseNotifications
};
