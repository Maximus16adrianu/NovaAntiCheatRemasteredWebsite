const crypto = require("crypto");
const fs = require("fs");
const { config } = require("./config");
const { HttpError, nowIso } = require("./utils");

const SECURE_ALGORITHM = "NOVA-RSA-OAEP-256+A256GCM";
const SECURE_VERSION = 1;
const AES_KEY_LENGTH = 32;
const GCM_IV_LENGTH = 12;
const GCM_TAG_LENGTH = 16;

const seenNonces = new Map();
let keyMaterialCache = null;

function initializePluginSecureTransport() {
  const keyMaterial = loadOrCreateKeyMaterial();
  syncPublicKeyExport(keyMaterial);
  return keyMaterial;
}

function getPluginSecureTransportInfo() {
  const keyMaterial = loadOrCreateKeyMaterial();
  return {
    algorithm: SECURE_ALGORITHM,
    fingerprint: keyMaterial.publicKeyFingerprint,
    publicKeyExportPath: config.pluginTransportPublicKeyExportPath,
    publicKeyPath: config.pluginTransportPublicKeyPath,
    privateKeyPath: config.pluginTransportPrivateKeyPath
  };
}

function pluginSecureTransportMiddleware(req, res, next) {
  res.pluginJson = (payload, status = 200) => sendPluginJson(req, res, payload, status);

  if (req.body && req.body.secure === true) {
    try {
      const decrypted = decryptPluginEnvelope(req.body);
      req.secureTransport = {
        aesKey: decrypted.aesKey,
        requestNonce: decrypted.requestNonce,
        requestTimestamp: decrypted.requestTimestamp
      };
      req.body = decrypted.body;
      return next();
    } catch (error) {
      return next(error);
    }
  }

  if (config.pluginSecureTransportRequired) {
    return next(new HttpError(426, "Secure plugin transport is required on this server."));
  }

  return next();
}

function sendPluginJson(req, res, payload, status = 200) {
  if (req?.secureTransport?.aesKey) {
    const envelope = encryptPluginResponse(req.secureTransport, payload, status);
    return res.status(status).json(envelope);
  }

  return res.status(status).json(payload);
}

function decryptPluginEnvelope(envelope) {
  if (!envelope || envelope.secure !== true) {
    throw new HttpError(400, "Missing secure transport envelope.");
  }

  if (envelope.alg !== SECURE_ALGORITHM) {
    throw new HttpError(400, "Unsupported secure transport algorithm.");
  }

  const encryptedKey = decodeBase64(envelope.key, "secure key");
  const iv = decodeBase64(envelope.iv, "secure iv");
  const payload = decodeBase64(envelope.payload, "secure payload");

  if (iv.length !== GCM_IV_LENGTH) {
    throw new HttpError(400, "Invalid secure IV length.");
  }
  if (payload.length <= GCM_TAG_LENGTH) {
    throw new HttpError(400, "Invalid secure payload length.");
  }

  const keyMaterial = loadOrCreateKeyMaterial();
  let aesKey;
  try {
    aesKey = crypto.privateDecrypt(
      {
        key: keyMaterial.privateKeyPem,
        padding: crypto.constants.RSA_PKCS1_OAEP_PADDING,
        oaepHash: "sha256"
      },
      encryptedKey
    );
  } catch (_error) {
    throw new HttpError(400, "Could not decrypt secure request key.");
  }

  if (aesKey.length !== AES_KEY_LENGTH) {
    throw new HttpError(400, "Invalid secure request key length.");
  }

  const ciphertext = payload.subarray(0, payload.length - GCM_TAG_LENGTH);
  const authTag = payload.subarray(payload.length - GCM_TAG_LENGTH);

  let plaintext;
  try {
    const decipher = crypto.createDecipheriv("aes-256-gcm", aesKey, iv);
    decipher.setAuthTag(authTag);
    plaintext = Buffer.concat([decipher.update(ciphertext), decipher.final()]).toString("utf8");
  } catch (_error) {
    throw new HttpError(400, "Secure request authentication failed.");
  }

  let parsed;
  try {
    parsed = JSON.parse(plaintext);
  } catch (_error) {
    throw new HttpError(400, "Secure request payload is not valid JSON.");
  }

  if (!parsed || parsed.v !== SECURE_VERSION || typeof parsed !== "object") {
    throw new HttpError(400, "Invalid secure request version.");
  }

  const requestNonce = String(parsed.nonce || "").trim();
  const requestTimestamp = String(parsed.ts || "").trim();
  const requestBody = parsed.body && typeof parsed.body === "object" ? parsed.body : {};

  if (!requestNonce) {
    throw new HttpError(400, "Missing secure request nonce.");
  }

  const parsedTimestamp = Date.parse(requestTimestamp);
  if (!Number.isFinite(parsedTimestamp)) {
    throw new HttpError(400, "Missing secure request timestamp.");
  }

  const maxSkewMs = Math.max(30, config.secureRequestMaxSkewSeconds) * 1000;
  if (Math.abs(Date.now() - parsedTimestamp) > maxSkewMs) {
    throw new HttpError(408, "Secure request expired or clock skew is too large.");
  }

  if (!rememberNonce(requestNonce)) {
    throw new HttpError(409, "Secure request replay detected.");
  }

  return {
    aesKey,
    requestNonce,
    requestTimestamp,
    body: requestBody
  };
}

function encryptPluginResponse(secureContext, payload, status = 200) {
  const iv = crypto.randomBytes(GCM_IV_LENGTH);
  const plaintext = Buffer.from(JSON.stringify({
    v: SECURE_VERSION,
    ts: nowIso(),
    responseTo: secureContext.requestNonce,
    statusCode: status,
    body: payload
  }), "utf8");

  const cipher = crypto.createCipheriv("aes-256-gcm", secureContext.aesKey, iv);
  const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  const authTag = cipher.getAuthTag();

  return {
    secure: true,
    alg: SECURE_ALGORITHM,
    iv: iv.toString("base64"),
    payload: Buffer.concat([ciphertext, authTag]).toString("base64")
  };
}

function loadOrCreateKeyMaterial() {
  if (keyMaterialCache) {
    return keyMaterialCache;
  }

  fs.mkdirSync(config.privateCryptoDir, { recursive: true });
  fs.mkdirSync(config.privateExportsDir, { recursive: true });

  let privateKeyPem;
  let publicKeyPem;

  if (fs.existsSync(config.pluginTransportPrivateKeyPath) && fs.existsSync(config.pluginTransportPublicKeyPath)) {
    privateKeyPem = fs.readFileSync(config.pluginTransportPrivateKeyPath, "utf8");
    publicKeyPem = fs.readFileSync(config.pluginTransportPublicKeyPath, "utf8");
  } else {
    const generated = crypto.generateKeyPairSync("rsa", {
      modulusLength: 3072,
      publicKeyEncoding: {
        type: "spki",
        format: "pem"
      },
      privateKeyEncoding: {
        type: "pkcs8",
        format: "pem"
      }
    });

    privateKeyPem = generated.privateKey;
    publicKeyPem = generated.publicKey;
    fs.writeFileSync(config.pluginTransportPrivateKeyPath, privateKeyPem, "utf8");
    fs.writeFileSync(config.pluginTransportPublicKeyPath, publicKeyPem, "utf8");
  }

  const publicKeyBase64 = pemToBase64(publicKeyPem);
  const publicKeyFingerprint = crypto
    .createHash("sha256")
    .update(Buffer.from(publicKeyBase64, "base64"))
    .digest("hex");

  keyMaterialCache = {
    privateKeyPem,
    publicKeyPem,
    publicKeyBase64,
    publicKeyFingerprint
  };

  return keyMaterialCache;
}

function syncPublicKeyExport(keyMaterial) {
  fs.writeFileSync(config.pluginTransportPublicKeyExportPath, `${keyMaterial.publicKeyBase64}\n`, "utf8");
}

function pemToBase64(pem) {
  return String(pem || "")
    .replace(/-----BEGIN PUBLIC KEY-----/g, "")
    .replace(/-----END PUBLIC KEY-----/g, "")
    .replace(/\s+/g, "");
}

function decodeBase64(value, label) {
  try {
    return Buffer.from(String(value || ""), "base64");
  } catch (_error) {
    throw new HttpError(400, `Invalid ${label}.`);
  }
}

function rememberNonce(nonce) {
  cleanupSeenNonces();
  const now = Date.now();
  const existingExpiry = seenNonces.get(nonce);
  if (existingExpiry && existingExpiry > now) {
    return false;
  }

  const maxSkewMs = Math.max(30, config.secureRequestMaxSkewSeconds) * 1000;
  seenNonces.set(nonce, now + Math.max(60_000, maxSkewMs * 2));
  return true;
}

function cleanupSeenNonces() {
  const now = Date.now();
  for (const [nonce, expiresAt] of seenNonces.entries()) {
    if (expiresAt <= now) {
      seenNonces.delete(nonce);
    }
  }
}

module.exports = {
  getPluginSecureTransportInfo,
  initializePluginSecureTransport,
  pluginSecureTransportMiddleware,
  sendPluginJson
};
