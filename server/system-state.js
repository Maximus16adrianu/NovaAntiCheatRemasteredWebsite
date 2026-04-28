const { getDatabase } = require("./database");
const { HttpError, normalizeText, nowIso, safeJsonParse } = require("./utils");

const LOCKDOWN_SETTING_KEY = "lockdown";

function getSystemSetting(settingKey, fallback = null) {
  const row = getDatabase().prepare(`
    SELECT value_json
      FROM system_settings
     WHERE setting_key = ?
  `).get(settingKey);

  if (!row?.value_json) {
    return fallback;
  }

  return safeJsonParse(row.value_json, fallback);
}

function setSystemSetting(settingKey, value) {
  const timestamp = nowIso();
  getDatabase().prepare(`
    INSERT INTO system_settings (
      setting_key,
      value_json,
      updated_at
    ) VALUES (?, ?, ?)
    ON CONFLICT(setting_key) DO UPDATE SET
      value_json = excluded.value_json,
      updated_at = excluded.updated_at
  `).run(settingKey, JSON.stringify(value ?? {}), timestamp);
}

function getLockdownState() {
  const stored = getSystemSetting(LOCKDOWN_SETTING_KEY, {});
  return {
    enabled: Boolean(stored?.enabled),
    reason: normalizeText(stored?.reason),
    actor: normalizeText(stored?.actor),
    since: normalizeText(stored?.since) || null,
    updatedAt: normalizeText(stored?.updatedAt) || null
  };
}

function setLockdownState({ enabled, reason = "", actor = "admin" } = {}) {
  const current = getLockdownState();
  const timestamp = nowIso();
  const nextState = {
    enabled: Boolean(enabled),
    reason: normalizeText(reason),
    actor: normalizeText(actor, "admin"),
    since: Boolean(enabled) ? (current.enabled ? current.since : timestamp) : null,
    updatedAt: timestamp
  };

  setSystemSetting(LOCKDOWN_SETTING_KEY, nextState);
  return nextState;
}

function assertPluginAccessAllowed() {
  const lockdown = getLockdownState();
  if (!lockdown.enabled) {
    return;
  }

  throw new HttpError(403, lockdown.reason
    ? `Nova auth is locked down by admin. ${lockdown.reason}`
    : "Nova auth is locked down by admin.");
}

function assertPublicAccessAllowed(featureLabel = "This feature") {
  const lockdown = getLockdownState();
  if (!lockdown.enabled) {
    return;
  }

  throw new HttpError(423, lockdown.reason
    ? `${featureLabel} is temporarily locked down. ${lockdown.reason}`
    : `${featureLabel} is temporarily locked down.`);
}

function assertAdminMutationAllowed() {
  const lockdown = getLockdownState();
  if (!lockdown.enabled) {
    return;
  }

  throw new HttpError(423, lockdown.reason
    ? `Admin data changes are locked down. ${lockdown.reason}`
    : "Admin data changes are locked down.");
}

module.exports = {
  assertAdminMutationAllowed,
  assertPluginAccessAllowed,
  assertPublicAccessAllowed,
  getLockdownState,
  setLockdownState
};
