param(
    [string]$OutputDirectory = "artifacts/release-gate",
    [switch]$SkipBuild,
    [switch]$SkipFaultReplay,
    [string]$MavenCommand,
    [string]$JavaCommand
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $root
$stamp = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 6)
$output = Join-Path $OutputDirectory $stamp
New-Item -ItemType Directory -Force -Path $output | Out-Null
$summary = [System.Collections.Generic.List[object]]::new()

function Resolve-Tool([string]$Name, [string[]]$Candidates) {
    if ($Name) {
        $explicit = Get-Command $Name -ErrorAction SilentlyContinue
        if ($explicit) { return $explicit.Source }
        if (Test-Path -LiteralPath $Name -PathType Leaf) { return (Resolve-Path -LiteralPath $Name).Path }
    }
    foreach ($candidate in $Candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) { return $candidate }
    }
    return $null
}

function Add-Blocked([string]$Name, [string]$Reason) {
    $summary.Add([pscustomobject]@{name=$Name;status='BLOCKED';reason=$Reason;log=$null})
}

$javaCandidates = @()
if ($env:JAVA_HOME) { $javaCandidates += Join-Path $env:JAVA_HOME 'bin/java.exe' }
$java = if ($JavaCommand) { Resolve-Tool $JavaCommand @() } else { Resolve-Tool 'java' $javaCandidates }
$python = Resolve-Tool 'python' @()
$node = Resolve-Tool 'node' @()
$mavenCandidates = @()
if ($env:MAVEN_HOME) { $mavenCandidates += Join-Path $env:MAVEN_HOME 'bin/mvn.cmd' }
$wrapperCache = Join-Path $env:USERPROFILE '.m2/wrapper/dists'
if (Test-Path -LiteralPath $wrapperCache) {
    $mavenCandidates += Get-ChildItem -Path $wrapperCache -Filter mvn.cmd -Recurse -File | Select-Object -ExpandProperty FullName
}
$maven = if ($MavenCommand) { Resolve-Tool $MavenCommand @() } else { Resolve-Tool 'mvn' $mavenCandidates }
if (-not $python) { Add-Blocked 'tool-python' "Required tool 'python' was not found on PATH." }
if (-not $node) { Add-Blocked 'tool-node' "Required tool 'node' was not found on PATH." }
if ((-not $SkipBuild -or -not $SkipFaultReplay) -and -not $java -and $env:JAVA_HOME) { Add-Blocked 'tool-java' "Java was not found under JAVA_HOME '$env:JAVA_HOME'." }
if (-not $SkipBuild -and -not $maven) { Add-Blocked 'tool-maven' "Required tool 'mvn' was not found on PATH, MAVEN_HOME, or the Maven Wrapper cache." }

function Invoke-Gate([string]$Name, [string]$File, [string]$Program, [string[]]$Arguments) {
    $log = Join-Path $output $File
    $start = Get-Date
    $executionError = $null
    try {
        & $Program @Arguments *> $log
        $code = $LASTEXITCODE
    } catch {
        $executionError = $_.Exception.Message
        $code = 1
    }
    $status = if ($code -eq 0) { 'PASSED' } else { 'FAILED' }
    $summary.Add([pscustomobject]@{name=$Name;status=$status;exitCode=$code;startedAt=$start.ToString('o');durationSeconds=[math]::Round(((Get-Date)-$start).TotalSeconds,2);log=$File;error=$executionError})
    if ($status -eq 'FAILED') { Write-Warning "Gate '$Name' failed with exit code $code. See $log" }
    return $code -eq 0
}

if ($SkipBuild) {
    $summary.Add([pscustomobject]@{name='unit-and-integration-tests';status='SKIPPED';reason='-SkipBuild supplied';log=$null})
    if (-not $SkipFaultReplay) {
        $summary.Add([pscustomobject]@{name='isolated-gateway-fault-replay';status='BLOCKED';reason='A fresh replay requires freshly built artifacts; omit -SkipBuild or pass -SkipFaultReplay.';log=$null})
    }
} else {
    if ($maven -and $java) {
        $buildPassed = Invoke-Gate 'unit-and-integration-tests' 'maven-verify.log' $maven @('-B','-ntp','-pl','rover-admin,rover-gateway-test/backend,rover-nameserver-bootstrap,rover-gateway-bootstrap','-am','clean','verify')
    } else {
        $buildPassed = $false
        Add-Blocked 'unit-and-integration-tests' 'Maven or Java executable is unavailable.'
    }
}

if ($python) {
    [void](Invoke-Gate 'replay-integrity-and-failure-injection' 'python-replay-tests.log' $python @('-m','unittest','deploy/scripts/test_agent_fault_replay.py'))
} else {
    Add-Blocked 'replay-integrity-and-failure-injection' 'Python executable is unavailable.'
}
if ($node) {
    [void](Invoke-Gate 'workbench-hypothesis-contract' 'node-hypotheses.log' $node @('deploy/scripts/test_workbench_hypotheses.cjs'))
    [void](Invoke-Gate 'workbench-release-timeline' 'node-timeline.log' $node @('deploy/scripts/test_workbench_timeline.cjs'))
} else {
    Add-Blocked 'workbench-contract-tests' 'Node executable is unavailable.'
}
if ($SkipFaultReplay) {
    $summary.Add([pscustomobject]@{name='isolated-gateway-fault-replay';status='SKIPPED';reason='-SkipFaultReplay supplied';log=$null})
} elseif ($SkipBuild) {
    # Already marked BLOCKED above; never replay against stale artifacts.
} elseif (-not $SkipBuild -and -not $buildPassed) {
    Add-Blocked 'isolated-gateway-fault-replay' 'Required Maven build did not pass.'
} elseif (-not $python -or -not $java) {
    Add-Blocked 'isolated-gateway-fault-replay' 'Python or Java executable is unavailable.'
} else {
    [void](Invoke-Gate 'isolated-gateway-fault-replay' 'fault-replay.log' $python @('deploy/scripts/agent-fault-replay.py','--java',$java,'--require-durable-receipts','--require-single-admin-foundation','--require-evidence-contract','--require-change-timeline','--require-hypothesis-contract'))
}

$failed = @($summary | Where-Object status -in @('FAILED','BLOCKED')).Count -gt 0
$skipped = @($summary | Where-Object status -eq 'SKIPPED').Count -gt 0
$status = if ($failed) { 'FAILED' } elseif ($skipped) { 'PASSED_WITH_SKIPS' } else { 'PASSED' }
[pscustomobject]@{status=$status;createdAt=(Get-Date).ToString('o');repositoryRoot=$root;tools=@{java=$java;python=$python;node=$node;maven=$maven};gates=$summary} | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 (Join-Path $output 'summary.json')
Write-Host "Release gate $status. Evidence: $output"
if ($failed) { exit 1 }
