const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const { config } = require("./config");
const { getDatabase } = require("./database");
const { isLicenseExpired } = require("./pricing");
const { HttpError, generateToken, normalizeIdentifier, normalizeText, nowIso } = require("./utils");

const JAR_DOWNLOAD_COOLDOWN_MS = 30_000;
const MAX_JAR_UPLOAD_BYTES = 250 * 1024 * 1024;
const JAR_ACCESS_SCOPES = Object.freeze({
  PUBLIC: "public",
  BUYERS: "buyers"
});
const JAR_RELEASE_CHANNELS = Object.freeze({
  STABLE: "stable",
  BETA: "beta",
  LEGACY: "legacy"
});

function normalizeJarName(value, fallback = "Nova Build") {
  const text = normalizeText(value, fallback).slice(0, 96);
  return text || fallback;
}

function normalizeJarNotes(value) {
  return normalizeText(value).slice(0, 600);
}

function normalizeJarChangelog(value) {
  return normalizeText(value).slice(0, 4_000);
}

function normalizeSupportedVersions(value) {
  return normalizeText(value).slice(0, 240);
}

function normalizeJarAccessScope(value, fallback = JAR_ACCESS_SCOPES.BUYERS) {
  const normalized = normalizeText(value, fallback).toLowerCase();
  return normalized === JAR_ACCESS_SCOPES.PUBLIC ? JAR_ACCESS_SCOPES.PUBLIC : JAR_ACCESS_SCOPES.BUYERS;
}

function normalizeJarReleaseChannel(value, fallback = JAR_RELEASE_CHANNELS.STABLE) {
  const normalized = normalizeText(value, fallback).toLowerCase();
  if (normalized === JAR_RELEASE_CHANNELS.BETA || normalized === JAR_RELEASE_CHANNELS.LEGACY) {
    return normalized;
  }
  return JAR_RELEASE_CHANNELS.STABLE;
}

function normalizeBooleanInput(value) {
  if (typeof value === "boolean") {
    return value;
  }
  const normalized = normalizeText(value).toLowerCase();
  return normalized === "1" || normalized === "true" || normalized === "yes" || normalized === "on";
}

function normalizeOriginalJarName(value) {
  const baseName = path.basename(normalizeText(value, "nova-build.jar")) || "nova-build.jar";
  if (!baseName.toLowerCase().endsWith(".jar")) {
    throw new HttpError(400, "Only .jar files can be uploaded.");
  }
  return baseName;
}

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

function buildJarStorageName(originalName) {
  return `${Date.now()}-${generateToken(10)}${path.extname(originalName).toLowerCase() || ".jar"}`;
}

function getJarFilePath(storedName) {
  return path.join(config.privateJarsDir, storedName);
}

function sanitizeDownloadFileName(displayName, originalName) {
  const source = normalizeText(displayName) || path.parse(normalizeText(originalName, "nova-build.jar")).name;
  const sanitized = source
    .replace(/[<>:"/\\|?*\u0000-\u001f]+/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 96) || "nova-build";
  return sanitized.toLowerCase().endsWith(".jar") ? sanitized : `${sanitized}.jar`;
}

function getReleaseChannelLabel(releaseChannel) {
  if (releaseChannel === JAR_RELEASE_CHANNELS.BETA) {
    return "Beta";
  }
  if (releaseChannel === JAR_RELEASE_CHANNELS.LEGACY) {
    return "Legacy";
  }
  return "Stable";
}

function serializeJar(row) {
  if (!row) {
    return null;
  }

  const accessScope = normalizeJarAccessScope(row.access_scope);
  const releaseChannel = normalizeJarReleaseChannel(row.release_channel);

  return {
    id: row.id,
    displayName: row.display_name,
    notes: row.notes || "",
    changelog: row.changelog || "",
    supportedVersions: row.supported_versions || "",
    releaseChannel,
    releaseChannelLabel: getReleaseChannelLabel(releaseChannel),
    accessScope,
    accessLabel: accessScope === JAR_ACCESS_SCOPES.PUBLIC ? "Everybody" : "Buyers only",
    requiresBuyerLicense: accessScope === JAR_ACCESS_SCOPES.BUYERS,
    recommended: Boolean(row.recommended),
    storedName: row.stored_name,
    originalName: row.original_name || "",
    fileSize: row.file_size || 0,
    mimeType: row.mime_type || "application/java-archive",
    sortOrder: row.sort_order || 0,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    downloadName: sanitizeDownloadFileName(row.display_name, row.original_name)
  };
}

function serializePublicJar(row) {
  const jar = serializeJar(row);
  if (!jar) {
    return null;
  }

  return {
    id: jar.id,
    displayName: jar.displayName,
    notes: jar.notes,
    changelog: jar.changelog,
    supportedVersions: jar.supportedVersions,
    releaseChannel: jar.releaseChannel,
    releaseChannelLabel: jar.releaseChannelLabel,
    accessScope: jar.accessScope,
    accessLabel: jar.accessLabel,
    requiresBuyerLicense: jar.requiresBuyerLicense,
    recommended: jar.recommended,
    originalName: jar.originalName,
    fileSize: jar.fileSize,
    updatedAt: jar.updatedAt,
    downloadName: jar.downloadName
  };
}

function listJarRows() {
  return getDatabase().prepare(`
    SELECT *
      FROM download_jars
     ORDER BY sort_order ASC, id ASC
  `).all();
}

function listAdminJars() {
  return listJarRows().map(serializeJar);
}

function getJarById(jarId) {
  const normalizedId = Number.parseInt(jarId, 10);
  if (!Number.isFinite(normalizedId)) {
    throw new HttpError(400, "Invalid jar id.");
  }

  const row = getDatabase().prepare(`
    SELECT *
      FROM download_jars
     WHERE id = ?
  `).get(normalizedId);

  if (!row) {
    throw new HttpError(404, "Jar not found.");
  }

  return row;
}

function getNextJarSortOrder() {
  const row = getDatabase().prepare("SELECT COALESCE(MAX(sort_order), 0) AS max_sort FROM download_jars").get();
  return (row?.max_sort || 0) + 1;
}

function resequenceJarOrders(database) {
  const rows = database.prepare(`
    SELECT id
      FROM download_jars
     ORDER BY sort_order ASC, id ASC
  `).all();

  const update = database.prepare("UPDATE download_jars SET sort_order = ?, updated_at = ? WHERE id = ?");
  const timestamp = nowIso();
  rows.forEach((row, index) => {
    update.run(index + 1, timestamp, row.id);
  });
}

function uploadJar({
  displayName,
  notes,
  changelog,
  supportedVersions,
  releaseChannel,
  recommended,
  accessScope,
  originalName,
  mimeType,
  buffer
}) {
  if (!Buffer.isBuffer(buffer) || buffer.length === 0) {
    throw new HttpError(400, "Upload body is empty.");
  }
  if (buffer.length > MAX_JAR_UPLOAD_BYTES) {
    throw new HttpError(413, "Jar exceeds the maximum upload size.");
  }

  const normalizedOriginalName = normalizeOriginalJarName(originalName);
  const normalizedDisplayName = normalizeJarName(displayName, path.parse(normalizedOriginalName).name);
  const normalizedNotes = normalizeJarNotes(notes);
  const normalizedChangelog = normalizeJarChangelog(changelog);
  const normalizedSupportedVersions = normalizeSupportedVersions(supportedVersions);
  const normalizedReleaseChannel = normalizeJarReleaseChannel(releaseChannel);
  const normalizedRecommended = normalizeBooleanInput(recommended) ? 1 : 0;
  const normalizedAccessScope = normalizeJarAccessScope(accessScope);
  const storedName = buildJarStorageName(normalizedOriginalName);
  const filePath = getJarFilePath(storedName);
  const timestamp = nowIso();
  const database = getDatabase();

  fs.writeFileSync(filePath, buffer);

  try {
    const insert = database.transaction(() => {
      if (normalizedRecommended) {
        database.prepare(`
          UPDATE download_jars
             SET recommended = 0,
                 updated_at = ?
           WHERE recommended = 1
        `).run(timestamp);
      }

      return database.prepare(`
        INSERT INTO download_jars (
          display_name,
          notes,
          changelog,
          supported_versions,
          release_channel,
          access_scope,
          recommended,
          stored_name,
          original_name,
          file_size,
          mime_type,
          sort_order,
          created_at,
          updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      `).run(
        normalizedDisplayName,
        normalizedNotes,
        normalizedChangelog,
        normalizedSupportedVersions,
        normalizedReleaseChannel,
        normalizedAccessScope,
        normalizedRecommended,
        storedName,
        normalizedOriginalName,
        buffer.length,
        normalizeText(mimeType, "application/java-archive"),
        getNextJarSortOrder(),
        timestamp,
        timestamp
      );
    })();

    return serializeJar(getJarById(insert.lastInsertRowid));
  } catch (error) {
    try {
      fs.rmSync(filePath, { force: true });
    } catch (_cleanupError) {
      // Ignore cleanup failures here; the DB error is the relevant one.
    }
    throw error;
  }
}

function updateJar(jarId, updates = {}) {
  const existing = getJarById(jarId);
  const database = getDatabase();
  const timestamp = nowIso();
  const nextDisplayName = normalizeJarName(updates.displayName, existing.display_name);
  const nextNotes = normalizeJarNotes(updates.notes ?? existing.notes);
  const nextChangelog = normalizeJarChangelog(updates.changelog ?? existing.changelog);
  const nextSupportedVersions = normalizeSupportedVersions(updates.supportedVersions ?? existing.supported_versions);
  const nextReleaseChannel = normalizeJarReleaseChannel(updates.releaseChannel ?? existing.release_channel, existing.release_channel);
  const nextAccessScope = normalizeJarAccessScope(updates.accessScope, existing.access_scope);
  const nextRecommended = updates.recommended === undefined
    ? (existing.recommended ? 1 : 0)
    : (normalizeBooleanInput(updates.recommended) ? 1 : 0);

  database.transaction(() => {
    if (nextRecommended) {
      database.prepare(`
        UPDATE download_jars
           SET recommended = 0,
               updated_at = ?
         WHERE id != ?
           AND recommended = 1
      `).run(timestamp, existing.id);
    }

    database.prepare(`
      UPDATE download_jars
         SET display_name = ?,
             notes = ?,
             changelog = ?,
             supported_versions = ?,
             release_channel = ?,
             access_scope = ?,
             recommended = ?,
             updated_at = ?
       WHERE id = ?
    `).run(
      nextDisplayName,
      nextNotes,
      nextChangelog,
      nextSupportedVersions,
      nextReleaseChannel,
      nextAccessScope,
      nextRecommended,
      timestamp,
      existing.id
    );
  })();

  return serializeJar(getJarById(existing.id));
}

function reorderJars(orderedIds = []) {
  const ids = Array.isArray(orderedIds)
    ? orderedIds.map((value) => Number.parseInt(value, 10)).filter(Number.isFinite)
    : [];
  const rows = listJarRows();

  if (rows.length === 0) {
    return [];
  }

  if (ids.length !== rows.length || new Set(ids).size !== rows.length) {
    throw new HttpError(400, "Reorder payload must include each jar exactly once.");
  }

  const existingIds = new Set(rows.map((row) => row.id));
  if (ids.some((id) => !existingIds.has(id))) {
    throw new HttpError(400, "Reorder payload includes an unknown jar.");
  }

  const database = getDatabase();
  const timestamp = nowIso();
  const update = database.prepare("UPDATE download_jars SET sort_order = ?, updated_at = ? WHERE id = ?");

  database.transaction(() => {
    ids.forEach((id, index) => {
      update.run(index + 1, timestamp, id);
    });
  })();

  return listAdminJars();
}

function deleteJar(jarId) {
  const existing = getJarById(jarId);
  const filePath = getJarFilePath(existing.stored_name);
  const database = getDatabase();

  database.transaction(() => {
    database.prepare("DELETE FROM download_jars WHERE id = ?").run(existing.id);
    resequenceJarOrders(database);
  })();

  try {
    fs.rmSync(filePath, { force: true });
  } catch (_error) {
    // Ignore missing-file cleanup failures; metadata is already removed.
  }

  return serializeJar(existing);
}

function getDownloadCooldown(ipAddress) {
  const row = getDatabase().prepare(`
    SELECT cooldown_until
      FROM jar_download_rate_limits
     WHERE ip_key = ?
  `).get(hashIpAddress(ipAddress));

  if (!row?.cooldown_until) {
    return 0;
  }

  const remaining = Date.parse(row.cooldown_until) - Date.now();
  return remaining > 0 ? remaining : 0;
}

function requireBuyerJarAccess(licenseKey, username) {
  const normalizedKey = normalizeText(licenseKey).toUpperCase();
  const normalizedUsername = normalizeIdentifier(username);
  if (!normalizedKey || !normalizedUsername) {
    throw new HttpError(403, "This jar is limited to active buyers. Enter the license user and key to continue.");
  }

  const license = getDatabase().prepare(`
    SELECT license_key,
           customer_username,
           active,
           expires_at
      FROM licenses
     WHERE license_key = ?
  `).get(normalizedKey);

  if (!license || !license.customer_username || normalizeIdentifier(license.customer_username) !== normalizedUsername) {
    throw new HttpError(403, "The license key and user do not match an active buyer license.");
  }
  if (!license.active) {
    throw new HttpError(403, "This license is disabled.");
  }
  if (isLicenseExpired(license.expires_at)) {
    throw new HttpError(403, "This license has expired.");
  }

  return license;
}

function listDownloadJarsForLicense(license) {
  const hasBuyerAccess = Boolean(license?.active) && !isLicenseExpired(license?.expires_at);
  return listJarRows()
    .map(serializePublicJar)
    .filter((jar) => !jar.requiresBuyerLicense || hasBuyerAccess);
}

function claimJarDownload(jarId, ipAddress, access = {}) {
  const jar = serializeJar(getJarById(jarId));
  if (jar.requiresBuyerLicense) {
    requireBuyerJarAccess(access.licenseKey, access.username);
  }

  const filePath = getJarFilePath(jar.storedName);
  if (!fs.existsSync(filePath)) {
    throw new HttpError(404, "Jar file is missing from storage.");
  }

  const database = getDatabase();
  const ipKey = hashIpAddress(ipAddress);
  const now = Date.now();
  const cooldownUntil = new Date(now + JAR_DOWNLOAD_COOLDOWN_MS).toISOString();
  const timestamp = new Date(now).toISOString();

  database.transaction(() => {
    const existing = database.prepare(`
      SELECT cooldown_until
        FROM jar_download_rate_limits
       WHERE ip_key = ?
    `).get(ipKey);

    const remaining = existing?.cooldown_until ? (Date.parse(existing.cooldown_until) - now) : 0;
    if (remaining > 0) {
      throw new HttpError(429, `You can download another jar in ${Math.ceil(remaining / 1000)} seconds.`, {
        cooldownRemainingMs: remaining,
        cooldownWindowMs: JAR_DOWNLOAD_COOLDOWN_MS
      });
    }

    if (existing) {
      database.prepare(`
        UPDATE jar_download_rate_limits
           SET cooldown_until = ?,
               last_download_at = ?,
               last_jar_id = ?
         WHERE ip_key = ?
      `).run(cooldownUntil, timestamp, jar.id, ipKey);
    } else {
      database.prepare(`
        INSERT INTO jar_download_rate_limits (
          ip_key,
          cooldown_until,
          last_download_at,
          last_jar_id
        ) VALUES (?, ?, ?, ?)
      `).run(ipKey, cooldownUntil, timestamp, jar.id);
    }
  })();

  return {
    jar,
    filePath,
    cooldownUntil,
    cooldownWindowMs: JAR_DOWNLOAD_COOLDOWN_MS
  };
}

function listPublicDownloadJars(ipAddress) {
  return {
    jars: listJarRows().map(serializePublicJar),
    cooldownRemainingMs: getDownloadCooldown(ipAddress),
    cooldownWindowMs: JAR_DOWNLOAD_COOLDOWN_MS
  };
}

module.exports = {
  claimJarDownload,
  deleteJar,
  getDownloadCooldown,
  listAdminJars,
  listDownloadJarsForLicense,
  listPublicDownloadJars,
  reorderJars,
  updateJar,
  uploadJar
};
