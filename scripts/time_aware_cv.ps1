<#
.SYNOPSIS
    Run blocked, rolling-origin evaluation for Bahreini and CADS.
.DESCRIPTION
    Uses the existing 29-day trace, seven days of past-only forecast history,
    five contiguous validation folds (four workload seeds/days per fold), and
    paired workloads across FCFS, Wiesner, Bahreini, deadline-bounded shift,
    and CADS thresholds. This is evaluation/model selection, not proof of
    out-of-sample generalisation beyond this short trace.
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\time_aware_cv.ps1
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$resultsRoot = Join-Path $root 'results\time_aware_cv'
New-Item -ItemType Directory -Force -Path $resultsRoot | Out-Null

Write-Host '[CV] Compiling project with repository JARs...' -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path 'bin' | Out-Null
$sources = (Get-ChildItem -Path 'src\main\java' -Filter '*.java' -Recurse).FullName
& javac -cp 'lib/*;bin' -d bin @sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
$cp = 'bin;lib/*'

$regions = @(
    @{ Label = 'GB'; Id = 'uk_clean'; Trace = 'data\uk\uk_carbon_hourly_clean.csv' },
    @{ Label = 'INDIA'; Id = 'india_gapfilled_linear'; Trace = 'data\india\india_carbon_hourly_utc_aligned.csv' }
)
foreach ($r in $regions) {
    if (-not (Test-Path $r.Trace)) { throw "Missing trace: $($r.Trace)" }
}

# Five blocked folds: seeds 1-4 map to days 7-10, 5-8 to days 11-14, ...,
# 17-20 to days 23-26. Every forecast uses only the preceding seven days.
for ($fold = 1; $fold -le 5; $fold++) {
    $firstSeed = (($fold - 1) * 4) + 1
    $seeds = @($firstSeed..($firstSeed + 3))
    $foldDir = Join-Path $resultsRoot ("fold_{0:D2}" -f $fold)
    New-Item -ItemType Directory -Force -Path $foldDir | Out-Null
    Write-Host "`n[CV] Fold $fold/5; seeds $($seeds -join ', ')" -ForegroundColor Cyan

    foreach ($r in $regions) {
        $out = Join-Path $foldDir ("{0}.csv" -f $r.Id)
        $javaArgs = @(
            '-Decocloud.startDays=rotate',
            '-Decocloud.info=forecast7d',
            '-Decocloud.cadsThetas=0,0.05,0.10',
            '-cp', $cp,
            'com.ecocloud.EcoCloudReview3MultiSeed',
            $r.Trace, $r.Label, $r.Id, $out
        ) + $seeds
        $log = Join-Path $foldDir ("{0}.log" -f $r.Id)
        $ErrorActionPreference = 'Continue'
        $javaOutput = & java @javaArgs 2>&1
        $javaExitCode = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        $javaExitCode = $LASTEXITCODE

        $javaOutput | Out-File -FilePath $log -Encoding utf8

        if ($javaExitCode -ne 0) {
            Write-Host "[CV] Java failed with exit code $javaExitCode"
            Get-Content $log -Tail 60
            exit $javaExitCode
        }
        if (-not (Test-Path $out)) {
            throw "Expected output CSV was not created: $out"
        }
        Write-Host ("  completed {0}; log: {1}" -f $r.Label, $log)
    }
}

Write-Host "`n[CV] Aggregating folds..." -ForegroundColor Cyan
python scripts\time_aware_cv_aggregate.py --input $resultsRoot
if ($LASTEXITCODE -ne 0) { throw 'CV aggregation failed.' }
Write-Host "[CV] Complete. See $resultsRoot\cv_summary.csv and fold_summary.csv" -ForegroundColor Green
