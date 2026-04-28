const crypto = require("crypto");
const { config } = require("./config");

function validateSecretValue(value, expectedValue) {
  const provided = Buffer.from(String(value || ""), "utf8");
  const expected = Buffer.from(String(expectedValue || ""), "utf8");

  if (provided.length === 0 || provided.length !== expected.length) {
    return false;
  }

  return crypto.timingSafeEqual(provided, expected);
}

function getAdminKeyFromRequest(req) {
  const headerKey = req.header("x-admin-key");
  if (headerKey) {
    return headerKey.trim();
  }

  const authHeader = req.header("authorization");
  if (authHeader && authHeader.toLowerCase().startsWith("bearer ")) {
    return authHeader.slice(7).trim();
  }

  return "";
}

function validateAdminKey(key) {
  return validateSecretValue(key, config.adminApiKey);
}

function getDownloadKeyFromRequest(req) {
  const headerKey = req.header("x-download-key");
  return headerKey ? headerKey.trim() : "";
}

function validateDownloadKey(key) {
  return validateSecretValue(key, config.downloadApiKey);
}

function requireAdmin(req, res, next) {
  if (!validateAdminKey(getAdminKeyFromRequest(req))) {
    return res.status(401).json({ ok: false, message: "Invalid admin API key." });
  }
  return next();
}

module.exports = {
  getDownloadKeyFromRequest,
  getAdminKeyFromRequest,
  requireAdmin,
  validateAdminKey,
  validateDownloadKey
};
