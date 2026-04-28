const crypto = require("crypto");
const { normalizeIdentifier, normalizeText, stableStringify } = require("./utils");

function normalizeMachinePayload(machine = {}) {
  return {
    hostName: normalizeIdentifier(machine.hostName || machine.hostname),
    pcName: normalizeText(machine.pcName || machine.deviceName || machine.hostName || machine.hostname, "Unknown Device"),
    userName: normalizeIdentifier(machine.userName || machine.username),
    osName: normalizeIdentifier(machine.osName),
    osVersion: normalizeIdentifier(machine.osVersion),
    osArch: normalizeIdentifier(machine.osArch),
    machineId: normalizeIdentifier(machine.machineId),
    processorIdentifier: normalizeIdentifier(machine.processorIdentifier || machine.cpuId || machine.cpu),
    availableProcessors: Number.parseInt(machine.availableProcessors || "0", 10) || 0
  };
}

function buildHwidFingerprint(machine = {}) {
  const normalized = normalizeMachinePayload(machine);
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
  normalizeMachinePayload
};
