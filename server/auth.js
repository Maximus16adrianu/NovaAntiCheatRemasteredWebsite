const crypto = require("crypto");
const { config } = require("./config");

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
  const provided = Buffer.from(String(key || ""), "utf8");
  const expected = Buffer.from(String(config.adminApiKey || ""), "utf8");

  if (provided.length === 0 || provided.length !== expected.length) {
    return false;
  }

  return crypto.timingSafeEqual(provided, expected);
}

function requireAdmin(req, res, next) {
  if (!validateAdminKey(getAdminKeyFromRequest(req))) {
    return res.status(401).json({ ok: false, message: "Invalid admin API key." });
  }
  return next();
}

module.exports = {
  getAdminKeyFromRequest,
  requireAdmin,
  validateAdminKey
};
