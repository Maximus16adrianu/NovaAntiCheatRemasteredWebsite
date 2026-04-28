const rateLimit = require("express-rate-limit");
const { sendPluginJson } = require("./secure-transport");
const { generateToken } = require("./utils");

const pluginLimiter = rateLimit({
  windowMs: 60_000,
  max: 240,
  standardHeaders: true,
  legacyHeaders: false
});

const publicLimiter = rateLimit({
  windowMs: 60_000,
  max: 80,
  standardHeaders: true,
  legacyHeaders: false
});

const adminLimiter = rateLimit({
  windowMs: 60_000,
  max: 120,
  standardHeaders: true,
  legacyHeaders: false
});

function requestIdMiddleware(req, _res, next) {
  req.requestId = generateToken(8);
  next();
}

function apiNotFoundHandler(req, res) {
  sendPluginJson(req, res, { ok: false, message: `Unknown API route: ${req.originalUrl}` }, 404);
}

function apiErrorHandler(error, req, res, _next) {
  const status = error?.status || 500;
  const payload = {
    ok: false,
    message: error?.message || "Internal server error.",
    requestId: req?.requestId || null
  };

  if (error?.details) {
    payload.details = error.details;
  }

  if (status >= 500) {
    console.error(`[NovaAC Website] API error on ${req?.originalUrl || "unknown"}:`, error);
  }

  sendPluginJson(req, res, payload, status);
}

module.exports = {
  adminLimiter,
  apiErrorHandler,
  apiNotFoundHandler,
  pluginLimiter,
  publicLimiter,
  requestIdMiddleware
};
