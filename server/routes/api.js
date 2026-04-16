const express = require("express");
const { pluginLimiter, publicLimiter } = require("../middleware");
const { pluginSecureTransportMiddleware } = require("../secure-transport");
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

  pluginRouter.use(pluginLimiter);
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
      const result = lookupResetState(req.body.licenseKey);
      res.json(result);
    } catch (error) {
      next(error);
    }
  });

  router.post("/reset/submit", publicLimiter, (req, res, next) => {
    try {
      const result = resetLicenseDevicesByKey(req.body.licenseKey, req.body.deviceIds);
      res.json({
        ...result,
        message: "Selected HWIDs were reset."
      });
    } catch (error) {
      next(error);
    }
  });

  return router;
}

module.exports = {
  createApiRouter
};
