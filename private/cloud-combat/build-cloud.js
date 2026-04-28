const fs = require("fs");
const https = require("https");
const path = require("path");
const { spawnSync } = require("child_process");

const root = __dirname;
const depsDir = path.join(root, ".deps");
const buildDir = path.join(root, "build");
const classesDir = path.join(buildDir, "classes");
const stageDir = path.join(buildDir, "stage");
const libsDir = path.join(buildDir, "libs");
const sourcesFile = path.join(buildDir, "sources.txt");
const manifestFile = path.join(buildDir, "MANIFEST.MF");

const deps = [
  ["gson-2.13.2.jar", "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar"],
  ["sqlite-jdbc-3.46.0.0.jar", "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.46.0.0/sqlite-jdbc-3.46.0.0.jar"],
  ["slf4j-api-1.7.32.jar", "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.32/slf4j-api-1.7.32.jar"],
  ["slf4j-simple-1.7.5.jar", "https://repo1.maven.org/maven2/org/slf4j/slf4j-simple/1.7.5/slf4j-simple-1.7.5.jar"],
  ["lombok-1.18.34.jar", "https://repo1.maven.org/maven2/org/projectlombok/lombok/1.18.34/lombok-1.18.34.jar"]
];

function javaTool(name) {
  const javaHome = process.env.JAVA_HOME;
  if (javaHome) {
    const candidate = path.join(javaHome, "bin", process.platform === "win32" ? `${name}.exe` : name);
    if (fs.existsSync(candidate)) {
      return candidate;
    }
  }
  if (process.platform === "win32") {
    const candidate = path.join("C:", "Program Files", "Java", "jdk-17", "bin", `${name}.exe`);
    if (fs.existsSync(candidate)) {
      return candidate;
    }
  }
  return name;
}

function download(url, destination) {
  return new Promise((resolve, reject) => {
    const request = https.get(url, (response) => {
      if (response.statusCode >= 300 && response.statusCode < 400 && response.headers.location) {
        response.resume();
        download(response.headers.location, destination).then(resolve, reject);
        return;
      }
      if (response.statusCode !== 200) {
        response.resume();
        reject(new Error(`Download failed ${response.statusCode}: ${url}`));
        return;
      }
      const output = fs.createWriteStream(destination);
      response.pipe(output);
      output.on("finish", () => output.close(resolve));
      output.on("error", reject);
    });
    request.on("error", reject);
  });
}

function walkJava(directory, output = []) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const fullPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      walkJava(fullPath, output);
    } else if (entry.isFile() && entry.name.endsWith(".java") && entry.name !== "NovaCombatCloudPlugin.java") {
      output.push(fullPath);
    }
  }
  return output;
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, { stdio: "inherit", ...options });
  if (result.status !== 0) {
    throw new Error(`${command} failed with exit code ${result.status}`);
  }
}

async function main() {
  fs.rmSync(buildDir, { recursive: true, force: true });
  fs.mkdirSync(depsDir, { recursive: true });
  fs.mkdirSync(classesDir, { recursive: true });
  fs.mkdirSync(stageDir, { recursive: true });
  fs.mkdirSync(libsDir, { recursive: true });

  const jars = [];
  for (const [name, url] of deps) {
    const jarPath = path.join(depsDir, name);
    if (!fs.existsSync(jarPath)) {
      console.log(`[cloud-combat] downloading ${name}`);
      await download(url, jarPath);
    }
    jars.push(jarPath);
  }

  const sources = walkJava(path.join(root, "src", "main", "java")).sort();
  fs.writeFileSync(sourcesFile, sources.map((source) => `"${source.replaceAll("\\", "/")}"`).join("\n"), "utf8");

  const classpath = jars.join(path.delimiter);
  const lombok = jars.find((jarPath) => path.basename(jarPath).startsWith("lombok-"));
  run(javaTool("javac"), [
    "-encoding", "UTF-8",
    "-cp", classpath,
    "-processorpath", lombok,
    "-d", classesDir,
    `@${sourcesFile}`
  ]);

  fs.cpSync(classesDir, stageDir, { recursive: true });
  const resourcesDir = path.join(root, "src", "main", "resources");
  if (fs.existsSync(resourcesDir)) {
    fs.cpSync(resourcesDir, stageDir, { recursive: true });
  }

  for (const jarPath of jars.filter((jarPath) => !path.basename(jarPath).startsWith("lombok-"))) {
    run(javaTool("jar"), ["xf", jarPath], { cwd: stageDir });
  }

  fs.writeFileSync(manifestFile, "Manifest-Version: 1.0\nMain-Class: me.cerial.nova.cloudcombat.CloudCombatServer\n\n", "utf8");
  run(javaTool("jar"), ["cfm", path.join(libsDir, "Nova-CombatCloud.jar"), manifestFile, "-C", stageDir, "."]);
  console.log("Built private/cloud-combat/build/libs/Nova-CombatCloud.jar");
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
