const fs = require("fs");
const path = require("path");
const dotenv = require("dotenv");

const ROOT_DIR = path.resolve(__dirname, "..");
dotenv.config({ path: path.join(ROOT_DIR, ".env") });

const config = Object.freeze({
  rootDir: ROOT_DIR,
  publicDir: path.join(ROOT_DIR, "public"),
  privateDir: path.join(ROOT_DIR, "private"),
  privateCryptoDir: path.join(ROOT_DIR, "private", "crypto"),
  privateBackupsDir: path.join(ROOT_DIR, "private", "backups"),
  privateCombatCloudDir: path.join(ROOT_DIR, "private", "cloud-combat"),
  privateExportsDir: path.join(ROOT_DIR, "private", "exports"),
  privateJarsDir: path.join(ROOT_DIR, "private", "jars"),
  combatCloudJarPath: process.env.NOVA_COMBAT_CLOUD_JAR || path.join(ROOT_DIR, "private", "cloud-combat", "build", "libs", "Nova-CombatCloud.jar"),
  combatCloudJavaCommand: process.env.NOVA_COMBAT_CLOUD_JAVA || "",
  combatCloudHost: process.env.NOVA_COMBAT_CLOUD_HOST || "0.0.0.0",
  combatCloudPort: 47564,
  combatCloudLogging: String(process.env.NOVA_COMBAT_CLOUD_LOGGING || "false").toLowerCase() === "true",
  pluginTransportPrivateKeyPath: path.join(ROOT_DIR, "private", "crypto", "plugin-transport-private.pem"),
  pluginTransportPublicKeyPath: path.join(ROOT_DIR, "private", "crypto", "plugin-transport-public.pem"),
  pluginTransportPublicKeyExportPath: path.join(ROOT_DIR, "private", "exports", "plugin-transport-public-key.txt"),
  databaseDir: path.join(ROOT_DIR, "database"),
  dbFilePath: path.join(ROOT_DIR, "database", "novaac.db"),
  port: Number.parseInt(process.env.PORT || "3003", 10),
  host: process.env.HOST || "0.0.0.0",
  adminApiKey: process.env.ADMIN_API_KEY || "change-me-admin-key",
  downloadApiKey: process.env.DOWNLOAD_API_KEY || "",
  defaultResetIntervalDays: Number.parseInt(process.env.DEFAULT_RESET_INTERVAL_DAYS || "30", 10),
  defaultHeartbeatSeconds: Number.parseInt(process.env.DEFAULT_HEARTBEAT_SECONDS || "10", 10),
  sessionStaleGraceSeconds: Number.parseInt(process.env.SESSION_STALE_GRACE_SECONDS || "10", 10),
  initialSessionGraceSeconds: Number.parseInt(process.env.INITIAL_SESSION_GRACE_SECONDS || "60", 10),
  pluginSecureTransportRequired: String(process.env.PLUGIN_SECURE_TRANSPORT_REQUIRED || "false").toLowerCase() === "true",
  secureRequestMaxSkewSeconds: Number.parseInt(process.env.SECURE_REQUEST_MAX_SKEW_SECONDS || "180", 10)
});

function ensureDirectories() {
  [
    config.rootDir,
    config.publicDir,
    config.privateDir,
    config.privateCryptoDir,
    config.privateBackupsDir,
    config.privateCombatCloudDir,
    config.privateExportsDir,
    config.privateJarsDir,
    config.databaseDir
  ].forEach((directory) => {
    fs.mkdirSync(directory, { recursive: true });
  });

  const gitkeepPath = path.join(config.privateExportsDir, ".gitkeep");
  if (!fs.existsSync(gitkeepPath)) {
    fs.writeFileSync(gitkeepPath, "", "utf8");
  }
}

module.exports = {
  config,
  ensureDirectories
};
