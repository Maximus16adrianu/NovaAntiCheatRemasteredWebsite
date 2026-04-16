const crypto = require("crypto");
const { normalizeIdentifier, normalizeText, stableStringify } = require("./utils");

function normalizeInstancePayload(instance = {}, server = {}) {
  return {
    instanceUuid: normalizeIdentifier(instance.instanceUuid || instance.uuid || instance.instanceId),
    instanceName: normalizeText(instance.instanceName || instance.name || server.serverName, "Unknown Instance"),
    serverRootHash: normalizeIdentifier(instance.serverRootHash || instance.rootHash),
    worldContainerHash: normalizeIdentifier(instance.worldContainerHash || instance.worldPathHash),
    pluginsPathHash: normalizeIdentifier(instance.pluginsPathHash || instance.pluginsFolderHash),
    serverIp: normalizeIdentifier(instance.serverIp || instance.ip),
    serverPort: Number.parseInt(instance.serverPort || instance.port || "0", 10) || 0
  };
}

function buildInstanceFingerprint(instance = {}, server = {}) {
  const normalized = normalizeInstancePayload(instance, server);
  const source = stableStringify({
    instanceUuid: normalized.instanceUuid,
    serverRootHash: normalized.serverRootHash,
    worldContainerHash: normalized.worldContainerHash,
    pluginsPathHash: normalized.pluginsPathHash
  });

  return {
    normalized,
    source,
    hash: crypto.createHash("sha256").update(source, "utf8").digest("hex")
  };
}

module.exports = {
  buildInstanceFingerprint,
  normalizeInstancePayload
};
