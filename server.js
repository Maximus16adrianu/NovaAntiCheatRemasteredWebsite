const path = require("path");
const express = require("express");
const { config, ensureDirectories } = require("./server/config");
const { processExpiringLicenseNotifications } = require("./server/activity");
const { initializeDatabase, closeDatabase } = require("./server/database");
const { requestIdMiddleware, apiNotFoundHandler, apiErrorHandler } = require("./server/middleware");
const { createApiRouter } = require("./server/routes/api");
const { createAdminRouter } = require("./server/routes/admin");
const { getPluginSecureTransportInfo, initializePluginSecureTransport } = require("./server/secure-transport");
const { reconcileSessions } = require("./server/sessions");

ensureDirectories();
initializeDatabase();
reconcileSessions();
initializePluginSecureTransport();
processExpiringLicenseNotifications().catch((error) => {
  console.error("[NovaAC Website] Initial expiry notification scan failed:", error);
});

const app = express();
app.disable("x-powered-by");

app.use(requestIdMiddleware);
app.use(express.json({ limit: "1mb" }));
app.use(express.urlencoded({ extended: false }));

app.get("/health", (_req, res) => {
  res.json({
    ok: true,
    name: "NovaAntiCheatRemasterdWebsite",
    database: path.basename(config.dbFilePath),
    time: new Date().toISOString()
  });
});

app.use("/api", createApiRouter());
app.use("/api/admin", createAdminRouter());

app.use(express.static(config.publicDir, { extensions: ["html"] }));
app.get("/", (_req, res) => res.sendFile(path.join(config.publicDir, "index.html")));
app.get("/legal", (_req, res) => res.sendFile(path.join(config.publicDir, "legal.html")));
app.get("/reset", (_req, res) => res.sendFile(path.join(config.publicDir, "index.html")));
app.get("/admin", (_req, res) => res.sendFile(path.join(config.publicDir, "admin.html")));

app.use("/api", apiNotFoundHandler);
app.use(apiErrorHandler);

const server = app.listen(config.port, config.host, () => {
  const secureTransport = getPluginSecureTransportInfo();
  console.log(`[NovaAC Website] Listening on http://${config.host}:${config.port}`);
  console.log(`[NovaAC Website] SQLite: ${config.dbFilePath}`);
  console.log(`[NovaAC Website] Plugin secure transport: ${secureTransport.algorithm}`);
  console.log(`[NovaAC Website] Plugin public key fingerprint: ${secureTransport.fingerprint}`);
  console.log(`[NovaAC Website] Plugin public key export: ${secureTransport.publicKeyExportPath}`);
});

const reconcileTimer = setInterval(() => {
  try {
    reconcileSessions();
  } catch (error) {
    console.error("[NovaAC Website] Session reconciliation failed:", error);
  }
}, 5_000);
reconcileTimer.unref();

const expiryNotificationTimer = setInterval(() => {
  processExpiringLicenseNotifications().catch((error) => {
    console.error("[NovaAC Website] Expiry notification scan failed:", error);
  });
}, 10 * 60 * 1000);
expiryNotificationTimer.unref();

function shutdown(signal) {
  console.log(`[NovaAC Website] ${signal} received, shutting down...`);
  clearInterval(reconcileTimer);
  clearInterval(expiryNotificationTimer);

  server.close(() => {
    closeDatabase();
    process.exit(0);
  });

  setTimeout(() => {
    console.error("[NovaAC Website] Forced shutdown after timeout.");
    closeDatabase();
    process.exit(1);
  }, 10_000).unref();
}

process.on("SIGINT", () => shutdown("SIGINT"));
process.on("SIGTERM", () => shutdown("SIGTERM"));
