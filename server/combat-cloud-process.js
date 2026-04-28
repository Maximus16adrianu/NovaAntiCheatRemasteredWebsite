const fs = require("fs");
const path = require("path");
const readline = require("readline");
const { spawn } = require("child_process");
const { config } = require("./config");

let combatCloudProcess = null;
let stopping = false;

function resolveJavaCommand() {
  if (config.combatCloudJavaCommand) {
    return config.combatCloudJavaCommand;
  }

  if (process.platform === "win32") {
    const candidates = [
      "C:\\Program Files\\Java\\jdk-17\\bin\\java.exe",
      "C:\\Program Files\\Java\\jdk-21\\bin\\java.exe"
    ];
    const existing = candidates.find((candidate) => fs.existsSync(candidate));
    if (existing) {
      return existing;
    }
  }

  return "java";
}

function pipeProcessOutput(stream, label, error = false) {
  const reader = readline.createInterface({ input: stream });
  reader.on("line", (line) => {
    const message = `[NovaAC CombatCloud] ${label}: ${line}`;
    if (error) {
      console.error(message);
    } else {
      console.log(message);
    }
  });
}

function startCombatCloudProcess() {
  if (combatCloudProcess) {
    return combatCloudProcess;
  }
  if (!fs.existsSync(config.combatCloudJarPath)) {
    throw new Error(`Combat cloud jar is missing: ${config.combatCloudJarPath}`);
  }

  const javaCommand = resolveJavaCommand();
  const args = [
    `-Dnova.combatcloud.db=${config.dbFilePath}`,
    `-Dnova.combatcloud.logging=${config.combatCloudLogging ? "true" : "false"}`,
    "-jar",
    config.combatCloudJarPath,
    String(config.combatCloudPort),
    config.combatCloudHost
  ];

  combatCloudProcess = spawn(javaCommand, args, {
    cwd: path.dirname(config.combatCloudJarPath),
    windowsHide: true,
    stdio: config.combatCloudLogging ? ["ignore", "pipe", "pipe"] : ["ignore", "ignore", "ignore"]
  });

  if (config.combatCloudLogging) {
    pipeProcessOutput(combatCloudProcess.stdout, "out");
    pipeProcessOutput(combatCloudProcess.stderr, "err", true);
  }

  combatCloudProcess.on("spawn", () => {
    console.log(`[NovaAC Website] Combat cloud started on ${config.combatCloudHost}:${config.combatCloudPort}`);
  });

  combatCloudProcess.on("error", (error) => {
    console.error("[NovaAC Website] Combat cloud failed to start:", error);
    if (!stopping) {
      process.exit(1);
    }
  });

  combatCloudProcess.on("exit", (code, signal) => {
    combatCloudProcess = null;
    if (stopping) {
      return;
    }
    console.error(`[NovaAC Website] Combat cloud exited unexpectedly (code=${code}, signal=${signal}).`);
    process.exit(1);
  });

  return combatCloudProcess;
}

function stopCombatCloudProcess() {
  stopping = true;
  const child = combatCloudProcess;
  combatCloudProcess = null;
  if (!child || child.killed) {
    return;
  }
  child.kill("SIGTERM");
  setTimeout(() => {
    if (!child.killed) {
      child.kill("SIGKILL");
    }
  }, 5_000).unref();
}

module.exports = {
  startCombatCloudProcess,
  stopCombatCloudProcess
};
