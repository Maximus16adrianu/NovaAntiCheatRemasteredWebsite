$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$javaHome = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
    $env:JAVA_HOME
} else {
    "C:\Program Files\Java\jdk-17"
}
$javac = Join-Path $javaHome "bin\javac.exe"
$jar = Join-Path $javaHome "bin\jar.exe"

if (!(Test-Path -LiteralPath $javac)) {
    $javac = "javac"
}
if (!(Test-Path -LiteralPath $jar)) {
    $jar = "jar"
}

$m2 = Join-Path $env:USERPROFILE ".m2\repository"
$gson = Join-Path $m2 "com\google\code\gson\gson\2.13.2\gson-2.13.2.jar"
$sqlite = Join-Path $m2 "org\xerial\sqlite-jdbc\3.46.0.0\sqlite-jdbc-3.46.0.0.jar"
$slf4jApi = Join-Path $m2 "org\slf4j\slf4j-api\1.7.32\slf4j-api-1.7.32.jar"
$slf4jSimple = Join-Path $m2 "org\slf4j\slf4j-simple\1.7.5\slf4j-simple-1.7.5.jar"
$lombok = Join-Path $m2 "org\projectlombok\lombok\1.18.34\lombok-1.18.34.jar"

foreach ($dependency in @($gson, $sqlite, $slf4jApi, $slf4jSimple, $lombok)) {
    if (!(Test-Path -LiteralPath $dependency)) {
        throw "Missing dependency: $dependency"
    }
}

$build = Join-Path $root "build"
$classes = Join-Path $build "classes"
$stage = Join-Path $build "stage"
$libs = Join-Path $build "libs"
$sourcesFile = Join-Path $build "sources.txt"
$manifestFile = Join-Path $build "MANIFEST.MF"

Remove-Item -LiteralPath $build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $stage, $libs | Out-Null

$sources = Get-ChildItem -Path (Join-Path $root "src\main\java") -Recurse -Filter "*.java" |
    Where-Object { $_.Name -ne "NovaCombatCloudPlugin.java" } |
    Sort-Object FullName

$sources | ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' } | Set-Content -LiteralPath $sourcesFile -Encoding ASCII

$classpath = @($gson, $sqlite, $slf4jApi, $slf4jSimple, $lombok) -join [IO.Path]::PathSeparator
& $javac -encoding UTF-8 -cp $classpath -processorpath $lombok -d $classes "@$sourcesFile"
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE"
}

Copy-Item -Path "$classes\*" -Destination $stage -Recurse -Force
if (Test-Path -LiteralPath (Join-Path $root "src\main\resources")) {
    Copy-Item -Path (Join-Path $root "src\main\resources\*") -Destination $stage -Recurse -Force
}

foreach ($dependency in @($gson, $sqlite, $slf4jApi, $slf4jSimple)) {
    Push-Location $stage
    try {
        & $jar xf $dependency
        if ($LASTEXITCODE -ne 0) {
            throw "jar extraction failed for $dependency with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

@"
Manifest-Version: 1.0
Main-Class: me.cerial.nova.cloudcombat.CloudCombatServer

"@ | Set-Content -LiteralPath $manifestFile -Encoding ASCII

& $jar cfm (Join-Path $libs "Nova-CombatCloud.jar") $manifestFile -C $stage .
if ($LASTEXITCODE -ne 0) {
    throw "jar failed with exit code $LASTEXITCODE"
}
Write-Host "Built cloud-combat\build\libs\Nova-CombatCloud.jar"
