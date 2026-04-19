const express = require("express");
const { pluginLimiter, publicLimiter } = require("../middleware");
const { claimJarDownload, listPublicDownloadJars } = require("../jars");
const { pluginSecureTransportMiddleware } = require("../secure-transport");
const { createSignedServerTimePayload } = require("../secure-transport");
const {
  activateLicense,
  heartbeatLicenseSession,
  lookupResetState,
  resetLicenseDevicesByKey,
  shutdownLicenseSession
} = require("../licenses");

function createApiRouter() {
  const router = express.Router();
  const pluginRouter = express.Router();

  function sendJarDownload(res, next, result) {
    res.setHeader("X-Download-Cooldown-Ms", String(result.cooldownWindowMs));
    res.setHeader("X-Download-Cooldown-Until", result.cooldownUntil);
    res.setHeader("X-Download-Name", encodeURIComponent(result.jar.downloadName));

    return res.download(result.filePath, result.jar.downloadName, (error) => {
      if (!error) {
        return;
      }
      if (!res.headersSent) {
        next(error);
        return;
      }
      console.error("[NovaAC Website] Jar download stream failed:", error);
    });
  }

  pluginRouter.use(pluginLimiter);

  pluginRouter.get("/time", (_req, res) => {
    res.json(createSignedServerTimePayload());
  });

  pluginRouter.use(pluginSecureTransportMiddleware);

  pluginRouter.post("/activate", (req, res, next) => {
    try {
      const result = activateLicense({
        licenseKey: req.body.licenseKey,
        username: req.body.username,
        pcName: req.body.pcName,
        machine: req.body.machine,
        instance: req.body.instance,
        server: req.body.server,
        ipAddress: req.ip,
        heartbeatIntervalSeconds: req.body.heartbeatIntervalSeconds
      });
      res.pluginJson(result);
    } catch (error) {
      next(error);
    }
  });

  pluginRouter.post("/heartbeat", (req, res, next) => {
    try {
      const result = heartbeatLicenseSession({
        sessionToken: req.body.sessionToken,
        serverName: req.body.serverName,
        pluginVersion: req.body.pluginVersion,
        instanceName: req.body.instanceName,
        ipAddress: req.ip
      });
      res.pluginJson(result);
    } catch (error) {
      next(error);
    }
  });

  pluginRouter.post("/shutdown", (req, res, next) => {
    try {
      const result = shutdownLicenseSession({
        sessionToken: req.body.sessionToken,
        reason: req.body.reason || "shutdown"
      });
      res.pluginJson(result);
    } catch (error) {
      next(error);
    }
  });

  router.use("/plugin", pluginRouter);

  router.post("/reset/lookup", publicLimiter, (req, res, next) => {
    try {
      const result = lookupResetState({
        licenseKey: req.body.licenseKey,
        username: req.body.username,
        ipAddress: req.ip
      });
      res.json(result);
    } catch (error) {
      next(error);
    }
  });

  router.post("/reset/submit", publicLimiter, (req, res, next) => {
    try {
      const result = resetLicenseDevicesByKey(req.body.licenseKey, req.body.username, req.body.deviceIds);
      res.json({
        ...result,
        message: "Selected HWIDs were reset."
      });
    } catch (error) {
      next(error);
    }
  });

  router.get("/downloads", publicLimiter, (req, res, next) => {
    try {
      const result = listPublicDownloadJars(req.ip);
      res.json({
        ok: true,
        ...result
      });
    } catch (error) {
      next(error);
    }
  });

  router.get("/downloads/:jarId", (req, res, next) => {
    try {
      const result = claimJarDownload(Number.parseInt(req.params.jarId, 10), req.ip);
      return sendJarDownload(res, next, result);
    } catch (error) {
      next(error);
    }
  });

  router.post("/downloads/:jarId", publicLimiter, (req, res, next) => {
    try {
      const result = claimJarDownload(Number.parseInt(req.params.jarId, 10), req.ip, {
        licenseKey: req.body?.licenseKey,
        username: req.body?.username
      });
      return sendJarDownload(res, next, result);
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = {
  createApiRouter
};
