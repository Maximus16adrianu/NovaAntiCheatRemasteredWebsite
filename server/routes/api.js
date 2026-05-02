const express = require("express");
const { pluginLimiter, publicLimiter } = require("../middleware");
const { claimJarDownload, listPublicDownloadJars } = require("../jars");
const { pluginSecureTransportMiddleware } = require("../secure-transport");
const { createSignedServerTimePayload } = require("../secure-transport");
const {
  activateLicense,
  blacklistLicenseDeviceFromAuthAttemptByKey,
  blacklistLicenseDeviceByKey,
  blacklistLicenseInstanceFromAuthAttemptByKey,
  blacklistLicenseInstanceByKey,
  clearLicenseSecurityListByKey,
  heartbeatLicenseSession,
  lookupManageState,
  lookupResetState,
  resetLicenseDevicesByKey,
  revokeAllLicenseDevicesByKey,
  revokeAllLicenseInstancesByKey,
  shutdownLicenseSession,
  sendSelfServiceWebhookTest,
  updateSelfServiceWebhook
} = require("../licenses");
const { assertPluginAccessAllowed, assertPublicAccessAllowed } = require("../system-state");

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
  pluginRouter.use((req, _res, next) => {
    try {
      assertPluginAccessAllowed();
      next();
    } catch (error) {
      next(error);
    }
  });

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
        hwid: req.body.hwid,
        machine: req.body.machine,
        deviceProfile: req.body.deviceProfile,
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

  router.post("/manage/lookup", publicLimiter, (req, res, next) => {
    try {
      assertPublicAccessAllowed("License management");
      const result = lookupManageState({
        licenseKey: req.body.licenseKey,
        username: req.body.username,
        ipAddress: req.ip
      });
      res.json(result);
    } catch (error) {
      next(error);
    }
  });

  router.post("/manage/reset", publicLimiter, (req, res, next) => {
    try {
      assertPublicAccessAllowed("License management");
      const result = resetLicenseDevicesByKey(req.body.licenseKey, req.body.username, req.body.deviceIds);
      res.json({
        ...result,
        message: "Selected HWIDs were reset."
      });
    } catch (error) {
      next(error);
    }
  });

  router.post("/manage/webhook", publicLimiter, (req, res, next) => {
    try {
      assertPublicAccessAllowed("License management");
      const result = updateSelfServiceWebhook(req.body.licenseKey, req.body.username, {
        webhookUrl: req.body.webhookUrl,
        events: req.body.events
      });
      res.json({
        ...result,
        message: result.webhook?.configured
          ? "Webhook saved."
          : "Webhook removed."
      });
    } catch (error) {
      next(error);
    }
  });

  router.post("/manage/webhook/test", publicLimiter, (req, res, next) => {
    Promise.resolve().then(async () => {
      assertPublicAccessAllowed("License management");
      const result = await sendSelfServiceWebhookTest(req.body.licenseKey, req.body.username, {
        webhookUrl: req.body.webhookUrl
      });
      res.json(result);
    }).catch(next);
  });

  router.post("/manage/revoke-devices", publicLimiter, (req, res, next) => {
    try {
      assertPublicAccessAllowed("License management");
      const result = revokeAllLicenseDevicesByKey(req.body.licenseKey, req.body.username);
      res.json(result);
    } catch (error) {
      next(error);
    }
  });

  router.post("/manage/revoke-instances", publicLimiter, (req, res, next) => {
    try {
      assertPublicAccessAllowed("License management");
      const result = revokeAllLicenseInstancesByKey(req.body.licenseKey, req.body.username);
      res.json(result);
    } catch (error) {
      next(error);
    }
  });

  router.post("/manage/security/device-blacklist", publicLimiter, (req, res, next) => {
    try {
      assertPublicAccessAllowed("License management");
      const result = blacklistLicenseDeviceByKey(req.body.licenseKey, req.body.username, req.body.deviceId);
      res.json(result);
    } catch (error) {
      next(error);
    }
  });

  router.post("/manage/security/instance-blacklist", publicLimiter, (req, res, next) => {
    try {
      assertPublicAccessAllowed("License management");
      const result = blacklistLicenseInstanceByKey(req.body.licenseKey, req.body.username, req.body.instanceId);
      res.json(result);
    } catch (error) {
      next(error);
    }
  });

  router.post("/manage/security/auth-attempt/device-blacklist", publicLimiter, (req, res, next) => {
    try {
      assertPublicAccessAllowed("License management");
      const result = blacklistLicenseDeviceFromAuthAttemptByKey(req.body.licenseKey, req.body.username, req.body.authAttemptId);
      res.json(result);
    } catch (error) {
      next(error);
    }
  });

  router.post("/manage/security/auth-attempt/instance-blacklist", publicLimiter, (req, res, next) => {
    try {
      assertPublicAccessAllowed("License management");
      const result = blacklistLicenseInstanceFromAuthAttemptByKey(req.body.licenseKey, req.body.username, req.body.authAttemptId);
      res.json(result);
    } catch (error) {
      next(error);
    }
  });

  router.post("/manage/security/clear", publicLimiter, (req, res, next) => {
    try {
      assertPublicAccessAllowed("License management");
      const result = clearLicenseSecurityListByKey(req.body.licenseKey, req.body.username);
      res.json(result);
    } catch (error) {
      next(error);
    }
  });

  router.post("/reset/lookup", publicLimiter, (req, res, next) => {
    try {
      assertPublicAccessAllowed("License management");
      const result = lookupManageState({
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
      assertPublicAccessAllowed("License management");
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
      assertPublicAccessAllowed("Jar downloads");
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
      assertPublicAccessAllowed("Jar downloads");
      const result = claimJarDownload(Number.parseInt(req.params.jarId, 10), req.ip);
      return sendJarDownload(res, next, result);
    } catch (error) {
      next(error);
    }
  });

  router.post("/downloads/:jarId", publicLimiter, (req, res, next) => {
    try {
      assertPublicAccessAllowed("Jar downloads");
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
