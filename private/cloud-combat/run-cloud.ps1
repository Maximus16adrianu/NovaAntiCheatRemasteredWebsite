$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$javaHome = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $env:JAVA_HOME
} else {
    "C:\Program Files\Java\jdk-17"
}
$java = Join-Path $javaHome "bin\java.exe"
if (!(Test-Path -LiteralPath $java)) {
    $java = "java"
}

$jar = Join-Path $root "build\libs\Nova-CombatCloud.jar"
if (!(Test-Path -LiteralPath $jar)) {
    throw "Cloud combat jar is missing. Run npm run build:cloud-combat first."
}

$db = if ($env:NOVA_COMBAT_CLOUD_DB) {
    $env:NOVA_COMBAT_CLOUD_DB
} else {
    Join-Path (Split-Path -Parent (Split-Path -Parent $root)) "database\novaac.db"
}

$port = "47564"
$hostName = if ($env:NOVA_COMBAT_CLOUD_HOST) { $env:NOVA_COMBAT_CLOUD_HOST } else { "0.0.0.0" }

& $java "-Dnova.combatcloud.db=$db" -jar $jar $port $hostName
