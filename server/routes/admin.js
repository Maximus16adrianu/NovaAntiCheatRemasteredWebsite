const fs = require("fs");
const path = require("path");
const express = require("express");
const archiver = require("archiver");
const { config } = require("../config");
const { getAuditLogFilterOptions, listAuditLogs, logAudit } = require("../activity");
const { adminLimiter } = require("../middleware");
const { getAdminKeyFromRequest, getDownloadKeyFromRequest, requireAdmin, validateAdminKey, validateDownloadKey } = require("../auth");
const { getDatabase } = require("../database");
const {
  deleteJar,
  listAdminJars,
  reorderJars,
  updateJar,
  uploadJar,
} = require("../jars");
const {
  addLicenseTime,
  adminResetDevices,
  adminResetInstances,
  createLicense,
  deleteLicense,
  extendLicenseTerm,
  getOverview,
  listLicenseDevices,
  listLicenseInstances,
  listLicenses,
  listRecentSessions,
  updateLicense
} = require("../licenses");
const { closeAllSessions, reconcileSessions } = require("../sessions");
const { assertAdminMutationAllowed, getLockdownState, setLockdownState } = require("../system-state");

function createAdminRouter() {
  const router = express.Router();
  router.use(adminLimiter);

  router.post("/login", (req, res) => {
    const key = req.body.apiKey || getAdminKeyFromRequest(req);
    if (!validateAdminKey(key)) {
      return res.status(401).json({ ok: false, message: "Invalid admin API key." });
    }

    return res.json({ ok: true, message: "Admin authentication successful." });
  });

  router.use(requireAdmin);
  router.use((req, _res, next) => {
    if (req.path === "/lockdown") {
      return next();
    }
    if (req.method === "GET" || req.method === "HEAD" || req.method === "OPTIONS") {
      return next();
    }

    try {
      assertAdminMutationAllowed();
      return next();
    } catch (error) {
      return next(error);
    }
  });

  router.get("/overview", (_req, res) => {
    res.json({
      ok: true,
      overview: getOverview()
    });
  });

  router.get("/lockdown", (_req, res) => {
    res.json({
      ok: true,
      lockdown: getLockdownState()
    });
  });

  router.post("/lockdown", (req, res, next) => {
    try {
      const enabled = Boolean(req.body?.enabled);
      const reason = req.body?.reason || "";
      const lockdown = setLockdownState({
        enabled,
        reason,
        actor: "admin"
      });
      const closedSessions = enabled ? closeAllSessions("admin_lockdown") : 0;

      logAudit({
        actor: "admin",
        action: enabled ? "system_lockdown_enabled" : "system_lockdown_disabled",
        details: {
          reason: lockdown.reason,
          closedSessions
        }
      });

      res.json({
        ok: true,
        lockdown,
        closedSessions,
        message: enabled
          ? "Lockdown enabled. New auth, downloads and data changes are blocked."
          : "Lockdown disabled."
      });
    } catch (error) {
      next(error);
    }
  });

  router.get("/licenses", (_req, res) => {
    res.json({
      ok: true,
      licenses: listLicenses()
    });
  });

  router.post("/licenses", (req, res, next) => {
    try {
      const license = createLicense(req.body || {});
      res.status(201).json({
        ok: true,
        license,
        message: "License created."
      });
    } catch (error) {
      next(error);
    }
  });

  router.patch("/licenses/:licenseId", (req, res, next) => {
    try {
      const license = updateLicense(Number.parseInt(req.params.licenseId, 10), req.body || {});
      res.json({
        ok: true,
        license,
        message: "License updated."
      });
    } catch (error) {
      next(error);
    }
  });

  router.post("/licenses/:licenseId/extend", (req, res, next) => {
    try {
      const license = extendLicenseTerm(Number.parseInt(req.params.licenseId, 10));
      res.json({
        ok: true,
        license,
        message: "License time extended."
      });
    } catch (error) {
      next(error);
    }
  });

  router.post("/licenses/:licenseId/add-time", (req, res, next) => {
    try {
      const license = addLicenseTime(Number.parseInt(req.params.licenseId, 10), req.body || {});
      res.json({
        ok: true,
        license,
        message: "License time added."
      });
    } catch (error) {
      next(error);
    }
  });

  router.delete("/licenses/:licenseId", (req, res, next) => {
    try {
      const result = deleteLicense(Number.parseInt(req.params.licenseId, 10));
      res.json({
        ok: true,
        ...result,
        message: "License deleted."
      });
    } catch (error) {
      next(error);
    }
  });

  router.get("/licenses/:licenseId/devices", (req, res, next) => {
    try {
      const licenseId = Number.parseInt(req.params.licenseId, 10);
      res.json({
        ok: true,
        devices: listLicenseDevices(licenseId)
      });
    } catch (error) {
      next(error);
    }
  });

  router.get("/licenses/:licenseId/instances", (req, res, next) => {
    try {
      const licenseId = Number.parseInt(req.params.licenseId, 10);
      res.json({
        ok: true,
        instances: listLicenseInstances(licenseId)
      });
    } catch (error) {
      next(error);
    }
  });

  router.post("/licenses/:licenseId/reset", (req, res, next) => {
    try {
      const licenseId = Number.parseInt(req.params.licenseId, 10);
      const result = adminResetDevices(licenseId, req.body.deviceIds);
      res.json({
        ok: true,
        ...result,
        message: "Selected devices were reset by admin."
      });
    } catch (error) {
      next(error);
    }
  });

  router.post("/licenses/:licenseId/reset-instances", (req, res, next) => {
    try {
      const licenseId = Number.parseInt(req.params.licenseId, 10);
      const result = adminResetInstances(licenseId, req.body.instanceIds);
      res.json({
        ok: true,
        ...result,
        message: "Selected instances were reset by admin."
      });
    } catch (error) {
      next(error);
    }
  });

  router.get("/sessions", (req, res) => {
    res.json({
      ok: true,
      sessions: listRecentSessions(Number.parseInt(req.query.limit || "150", 10))
    });
  });

  router.get("/audit-logs", (req, res, next) => {
    try {
      res.json({
        ok: true,
        filters: getAuditLogFilterOptions(),
        logs: listAuditLogs({
          licenseId: req.query.licenseId,
          actor: req.query.actor,
          action: req.query.action,
          search: req.query.search,
          limit: req.query.limit
        })
      });
    } catch (error) {
      next(error);
    }
  });

  router.get("/jars", (_req, res) => {
    res.json({
      ok: true,
      jars: listAdminJars()
    });
  });

  router.post("/jars/upload", express.raw({ type: "*/*", limit: "250mb" }), (req, res, next) => {
    try {
      const jar = uploadJar({
        displayName: req.query.displayName,
        notes: req.query.notes,
        changelog: req.query.changelog,
        supportedVersions: req.query.supportedVersions,
        releaseChannel: req.query.releaseChannel,
        recommended: req.query.recommended,
        accessScope: req.query.accessScope,
        originalName: req.query.originalName,
        mimeType: req.header("content-type"),
        buffer: req.body
      });
      res.status(201).json({
        ok: true,
        jar,
        message: "Jar uploaded."
      });
    } catch (error) {
      next(error);
    }
  });

  router.patch("/jars/:jarId", (req, res, next) => {
    try {
      const jar = updateJar(Number.parseInt(req.params.jarId, 10), req.body || {});
      res.json({
        ok: true,
        jar,
        message: "Jar updated."
      });
    } catch (error) {
      next(error);
    }
  });

  router.post("/jars/reorder", (req, res, next) => {
    try {
      const jars = reorderJars(req.body?.orderedIds);
      res.json({
        ok: true,
        jars,
        message: "Jar order updated."
      });
    } catch (error) {
      next(error);
    }
  });

  router.delete("/jars/:jarId", (req, res, next) => {
    try {
      const deleted = deleteJar(Number.parseInt(req.params.jarId, 10));
      res.json({
        ok: true,
        deleted,
        message: "Jar deleted."
      });
    } catch (error) {
      next(error);
    }
  });

  router.post("/maintenance/reconcile", (_req, res) => {
    reconcileSessions();
    res.json({
      ok: true,
      message: "Session reconciliation completed.",
      overview: getOverview()
    });
  });

  router.get("/backup", (req, res, next) => {
    try {
      if (!validateAdminKey(getAdminKeyFromRequest(req))) {
        return res.status(401).json({ ok: false, message: "Invalid admin API key." });
      }

      if (!validateDownloadKey(getDownloadKeyFromRequest(req))) {
        return res.status(401).json({ ok: false, message: "Invalid download API key." });
      }

      const database = getDatabase();
      try {
        database.pragma("wal_checkpoint(TRUNCATE)");
      } catch (error) {
        return res.status(500).json({ ok: false, message: "Failed to prepare the database for backup." });
      }

      const archiveName = `novaac-backup-${new Date().toISOString().split("T")[0]}.zip`;
      res.setHeader("Content-Type", "application/zip");
      res.setHeader("Content-Disposition", `attachment; filename="${archiveName}"`);

      const archive = archiver("zip", {
        zlib: { level: 9 }
      });

      archive.on("error", (error) => {
        if (!res.headersSent) {
          res.status(500).json({ ok: false, message: "Failed to create backup archive." });
        } else {
          res.destroy(error);
        }
      });

      archive.pipe(res);

      if (fs.existsSync(config.databaseDir)) {
        archive.directory(config.databaseDir, "database");
      }

      if (fs.existsSync(config.privateDir)) {
        archive.glob("**/*", {
          cwd: config.privateDir,
          dot: true,
          ignore: ["backups/**", "backups/**/*"]
        }, {
          prefix: "private"
        });
      }

      const envPath = path.join(config.rootDir, ".env");
      if (fs.existsSync(envPath)) {
        archive.file(envPath, { name: ".env" });
      }

      archive.finalize().catch(next);
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = {
  createAdminRouter
};
