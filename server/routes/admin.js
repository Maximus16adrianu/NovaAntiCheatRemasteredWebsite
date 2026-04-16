const express = require("express");
const { adminLimiter } = require("../middleware");
const { getAdminKeyFromRequest, requireAdmin, validateAdminKey } = require("../auth");
const {
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
const { reconcileSessions } = require("../sessions");

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

  router.get("/overview", (_req, res) => {
    res.json({
      ok: true,
      overview: getOverview()
    });
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

  router.post("/maintenance/reconcile", (_req, res) => {
    reconcileSessions();
    res.json({
      ok: true,
      message: "Session reconciliation completed.",
      overview: getOverview()
    });
  });

  return router;
}

module.exports = {
  createAdminRouter
};
