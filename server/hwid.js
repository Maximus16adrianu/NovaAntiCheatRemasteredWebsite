const crypto = require("crypto");
const { normalizeIdentifier, normalizeText, stableStringify } = require("./utils");

function normalizeInteger(value, fallback = 0, min = 0, max = Number.MAX_SAFE_INTEGER) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed)) {
    return fallback;
  }
  return Math.min(max, Math.max(min, parsed));
}

function normalizeBoolean(value, fallback = false) {
  if (value === undefined || value === null) {
    return fallback;
  }
  if (typeof value === "boolean") {
    return value;
  }

  const normalized = normalizeText(value).toLowerCase();
  if (["true", "1", "yes", "on"].includes(normalized)) {
    return true;
  }
  if (["false", "0", "no", "off"].includes(normalized)) {
    return false;
  }
  return fallback;
}

function normalizeLimitedText(value, fallback = "", maxLength = 240) {
  return normalizeText(value, fallback).slice(0, maxLength);
}

function normalizeHwidPayload(hwid = {}) {
  const source = hwid || {};
  return {
    hostName: normalizeIdentifier(source.hostName || source.hostname),
    userName: normalizeIdentifier(source.userName || source.username),
    osName: normalizeIdentifier(source.osName),
    osVersion: normalizeIdentifier(source.osVersion),
    osArch: normalizeIdentifier(source.osArch),
    machineId: normalizeIdentifier(source.machineId),
    processorIdentifier: normalizeIdentifier(source.processorIdentifier || source.cpuId || source.cpu),
    availableProcessors: normalizeInteger(source.availableProcessors)
  };
}

function normalizeDeviceProfilePayload(profile = {}, fallback = {}) {
  const source = profile || {};
  const backup = fallback || {};
  return {
    profileVersion: normalizeInteger(source.profileVersion, 1, 1, 20),
    hostName: normalizeLimitedText(source.hostName || source.hostname || backup.hostName, "", 160),
    pcName: normalizeLimitedText(
      source.pcName || source.deviceName || backup.pcName || backup.hostName || source.hostName || source.hostname,
      "Unknown Device",
      160
    ),
    userName: normalizeLimitedText(source.userName || source.username || backup.userName, "", 160),
    osName: normalizeLimitedText(source.osName || backup.osName, "", 160),
    osVersion: normalizeLimitedText(source.osVersion || backup.osVersion, "", 160),
    osArch: normalizeLimitedText(source.osArch || backup.osArch, "", 80),
    machineId: normalizeLimitedText(source.machineId || backup.machineId, "", 240),
    processorIdentifier: normalizeLimitedText(source.processorIdentifier || source.cpuId || source.cpu || backup.processorIdentifier, "", 240),
    availableProcessors: normalizeInteger(source.availableProcessors ?? backup.availableProcessors),
    containerEnvironment: normalizeBoolean(source.containerEnvironment),
    javaVersion: normalizeLimitedText(source.javaVersion, "", 80),
    javaVendor: normalizeLimitedText(source.javaVendor, "", 160),
    javaVmName: normalizeLimitedText(source.javaVmName, "", 160),
    javaRuntimeName: normalizeLimitedText(source.javaRuntimeName, "", 160),
    timezone: normalizeLimitedText(source.timezone, "", 100),
    locale: normalizeLimitedText(source.locale, "", 60),
    maxMemoryMb: normalizeInteger(source.maxMemoryMb),
    totalMemoryMb: normalizeInteger(source.totalMemoryMb),
    freeMemoryMb: normalizeInteger(source.freeMemoryMb),
    cpuModel: normalizeLimitedText(source.cpuModel, "", 240),
    gpuName: normalizeLimitedText(source.gpuName, "", 240),
    systemManufacturer: normalizeLimitedText(source.systemManufacturer, "", 160),
    systemModel: normalizeLimitedText(source.systemModel, "", 160)
  };
}

function hasHwidIdentity(normalized = {}) {
  return Boolean(
    normalized.hostName
    || normalized.userName
    || normalized.osName
    || normalized.osVersion
    || normalized.osArch
    || normalized.machineId
    || normalized.processorIdentifier
    || normalized.availableProcessors > 0
  );
}

function buildHwidFingerprint(hwid = {}) {
  const normalized = normalizeHwidPayload(hwid);
  const source = stableStringify({
    hostName: normalized.hostName,
    userName: normalized.userName,
    osName: normalized.osName,
    osVersion: normalized.osVersion,
    osArch: normalized.osArch,
    machineId: normalized.machineId,
    processorIdentifier: normalized.processorIdentifier,
    availableProcessors: normalized.availableProcessors
  });

  return {
    normalized,
    source,
    hash: crypto.createHash("sha256").update(source, "utf8").digest("hex")
  };
}

module.exports = {
  buildHwidFingerprint,
  hasHwidIdentity,
  normalizeDeviceProfilePayload,
  normalizeHwidPayload
};
