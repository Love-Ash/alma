$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$classPath = Join-Path $projectRoot 'verification\core-classes'
New-Item -ItemType Directory -Force -Path $classPath | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'care-core\src') -Recurse -Filter '*.java' | ForEach-Object FullName)
if ($sources.Count -lt 2) { throw 'Core source and behavioral test required' }
& javac -encoding UTF-8 -d $classPath @sources
if ($LASTEXITCODE -ne 0) { throw 'Core compile failed' }
& java -ea -cp $classPath ai.minash.gyeot.core.CareEngineTest
if ($LASTEXITCODE -ne 0) { throw 'Core tests failed' }
Write-Output 'CORE_TESTS_PASSED'
