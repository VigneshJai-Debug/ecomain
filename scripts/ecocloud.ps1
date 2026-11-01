[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet("build", "test", "audit", "sim", "matched", "cv", "plot", "preprocess", "all", "clean")]
    [string]$Action = "all",

    [string]$Algorithm = "All",

    [int]$Seed = 20260320
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$BinDir = Join-Path $RepoRoot "bin"
$LibDir = Join-Path $RepoRoot "lib"
$ResultsDir = Join-Path $RepoRoot "results"

function Invoke-Java {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ClassName,

        [string[]]$Arguments = @()
    )

    $ClassPath = "lib/*;bin"

    Write-Host "`nRunning: $ClassName $($Arguments -join ' ')"

    & java -cp $ClassPath $ClassName @Arguments

    if ($LASTEXITCODE -ne 0) {
        throw "Java execution failed: $ClassName (exit code $LASTEXITCODE)"
    }
}

function Invoke-EcoCloudPreprocess {
    Write-Host "`n=== DATA PREPROCESSING & TIMEZONE ALIGNMENT ==="

    Write-Host "Running India CEA trace preprocessing (IST -> UTC alignment)..."
    & python scripts/process_india_data.py
    if ($LASTEXITCODE -ne 0) { throw "India trace preprocessing failed." }

    Write-Host "Running UK NESO trace cleaning..."
    & python scripts/process_uk_data.py
    if ($LASTEXITCODE -ne 0) { throw "UK trace processing failed." }

    Write-Host "Running automated India preprocessing test suite..."
    & python scripts/test_india_preprocessing.py
    if ($LASTEXITCODE -ne 0) { throw "India preprocessing test suite failed." }

    Write-Host "Data preprocessing and timezone verification successful." -ForegroundColor Green
}

function Invoke-EcoCloudBuild {
    Write-Host "`n=== BUILD ==="

    if (Test-Path $BinDir) {
        Remove-Item -Recurse -Force $BinDir
    }
    New-Item -ItemType Directory -Force -Path $BinDir | Out-Null

    $Sources = @(
        Get-ChildItem -Path (Join-Path $RepoRoot "src\main\java") -Recurse -File -Filter "*.java"
    )

    if ($Sources.Count -eq 0) {
        throw "No Java source files found under src/main/java"
    }

    $SourceList = Join-Path $env:TEMP "ecocloud_sources.txt"
    $RootPrefix = $RepoRoot.TrimEnd('\', '/') + '\'

    $SourceLines = @(
        foreach ($Source in $Sources) {
            $FullPath = $Source.FullName

            if ($FullPath.StartsWith(
                $RootPrefix,
                [System.StringComparison]::OrdinalIgnoreCase
            )) {
                $RelativePath = $FullPath.Substring($RootPrefix.Length)
            }
            else {
                $RelativePath = $FullPath
            }

            '"' + $RelativePath.Replace('\', '/') + '"'
        }
    )

    [System.IO.File]::WriteAllLines(
        $SourceList,
        [string[]]$SourceLines,
        [System.Text.UTF8Encoding]::new($false)
    )

    Push-Location $RepoRoot

    try {
        & javac -encoding UTF-8 -cp "lib/*;bin" -d bin "@$SourceList"

        if ($LASTEXITCODE -ne 0) {
            throw "Java compilation failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }

    Write-Host "Build successful. Compiled $($Sources.Count) Java source files."
}

function Invoke-EcoCloudTest {
    Write-Host "`n=== TESTS ==="

    Invoke-Java "com.ecocloud.AuditValidationTest"
    Invoke-Java "com.ecocloud.TestCarbonTrace"
    Invoke-Java "com.ecocloud.TestCarbonScheduler"
}

function Invoke-EcoCloudAudit {
    Write-Host "`n=== NUMERICAL AUDIT ==="

    Invoke-Java "com.ecocloud.audit.EcoCloudLpRoundingAudit"
}

function Invoke-EcoCloudSimulations {
    Write-Host "`n=== FORECAST-GUIDED SIMULATIONS ==="

    if ($Algorithm -eq "All") {
        Invoke-Java "com.ecocloud.EcoCloudForecastGuidedExperiment" @("--all")
    }
    else {
        Invoke-Java "com.ecocloud.EcoCloudForecastGuidedExperiment" @(
            "--algorithm", $Algorithm,
            "--seed", "$Seed"
        )
    }
}

function Invoke-Matched60JobExperiment {
    Write-Host "`n=== MATCHED 60-JOB COMPARISON: FCFS / WIESNER / BAHREINI / DEADLINE SHIFT / CADS ==="

    $IndiaTrace = "data/india/india_carbon_hourly_utc_aligned.csv"
    $GbTrace = "data/uk/uk_carbon_hourly_clean.csv"
    $OutDir = "results/matched_60job"
    $Seeds = @("20260320", "20260321", "20260322")

    foreach ($Trace in @($IndiaTrace, $GbTrace)) {
        if (-not (Test-Path $Trace)) {
            throw "Required carbon trace not found: $Trace"
        }
    }

    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

    $OldJavaToolOptions = $env:JAVA_TOOL_OPTIONS
    try {
        $env:JAVA_TOOL_OPTIONS = "-Decocloud.startDays=fixed -Decocloud.info=oracle -Decocloud.cadsThetas=0,0.05,0.1 -Decocloud.cadsAdaptive=false -Decocloud.horizonSlots=48 -Decocloud.releaseSlots=8 -Decocloud.slackRange=12"

        $IndiaArgs = @($IndiaTrace, "INDIA", "india_matched60", "$OutDir/india_raw.csv") + $Seeds
        $GbArgs = @($GbTrace, "GB", "gb_matched60", "$OutDir/gb_raw.csv") + $Seeds
        Invoke-Java "com.ecocloud.EcoCloudReview3MultiSeed" $IndiaArgs
        Invoke-Java "com.ecocloud.EcoCloudReview3MultiSeed" $GbArgs
    }
    finally {
        $env:JAVA_TOOL_OPTIONS = $OldJavaToolOptions
    }

    # Fail closed if the harness did not produce all expected paired rows.
    foreach ($File in @("$OutDir/india_raw.csv", "$OutDir/gb_raw.csv")) {
        if (-not (Test-Path $File)) { throw "Matched experiment output missing: $File" }
        $Rows = @(Import-Csv $File)
        $ExpectedAlgorithms = @("FCFS", "Wiesner_2021_AdHoc", "Bahreini_SRR_APX", "Deadline_Carbon_Shift", "CADS_theta0", "CADS_theta5", "CADS_theta10")
        foreach ($SeedValue in $Seeds) {
            foreach ($Algo in $ExpectedAlgorithms) {
                $Found = @($Rows | Where-Object { $_.seed -eq $SeedValue -and $_.algorithm -eq $Algo })
                if ($Found.Count -ne 1) {
                    throw "Validation failed for $File seed=$SeedValue algorithm=$Algo (found $($Found.Count) rows)."
                }
            }
        }
    }

    # Build summaries from fresh raw CSVs
    foreach ($RegionFile in @("$OutDir/india_raw.csv", "$OutDir/gb_raw.csv")) {
        $Rows = @(Import-Csv $RegionFile)
        $SummaryRows = @()
        $Algorithms = @($Rows | Select-Object -ExpandProperty algorithm -Unique)
        foreach ($Algo in $Algorithms) {
            $AlgoRows = @($Rows | Where-Object { $_.algorithm -eq $Algo })
            $SeedSavings = @()
            foreach ($R in $AlgoRows) {
                $Baseline = $Rows | Where-Object { $_.seed -eq $R.seed -and $_.algorithm -eq "FCFS" } | Select-Object -First 1
                if ($Baseline -and [double]$Baseline.co2e_g -gt 0) {
                    $SeedSavings += 100.0 * ([double]$Baseline.co2e_g - [double]$R.co2e_g) / [double]$Baseline.co2e_g
                }
            }
            $MeanCo2 = ($AlgoRows | Measure-Object -Property co2e_g -Average).Average
            $MeanCompleted = ($AlgoRows | Measure-Object -Property completed_jobs -Average).Average
            $MeanViolations = ($AlgoRows | Measure-Object -Property deadline_violations -Average).Average
            $MeanViolations60 = ($AlgoRows | Measure-Object -Property deadline_violations_over_60s -Average).Average
            $MeanPerCompleted = ($AlgoRows | Measure-Object -Property co2e_per_completed_job_g -Average).Average
            $MeanSaving = if ($SeedSavings.Count -gt 0) { ($SeedSavings | Measure-Object -Average).Average } else { 0 }
            $SummaryRows += [PSCustomObject]@{
                region = $AlgoRows[0].region
                algorithm = $Algo
                seeds = $AlgoRows.Count
                mean_co2e_g = [math]::Round($MeanCo2, 3)
                mean_saving_vs_paired_fcfs_pct = [math]::Round($MeanSaving, 3)
                mean_completed_jobs = [math]::Round($MeanCompleted, 3)
                mean_co2e_per_completed_job_g = [math]::Round($MeanPerCompleted, 3)
                mean_deadline_violations = [math]::Round($MeanViolations, 3)
                mean_deadline_violations_over_60s = [math]::Round($MeanViolations60, 3)
            }
        }
        $SummaryPath = $RegionFile.Replace("_raw.csv", "_summary.csv")
        $SummaryRows | Export-Csv -NoTypeInformation -Encoding UTF8 -Path $SummaryPath
    }

    $Config = @{
        experiment = "matched_60job"
        jobs_per_seed = 60
        seeds = $Seeds
        regions = @("INDIA", "GB")
        information_mode = "oracle"
        start_days = "fixed"
        horizon_slots = 48
        release_slots = 8
        slack_range = 12
        cads_thetas = @(0, 0.05, 0.1)
        india_trace = $IndiaTrace
        gb_trace = $GbTrace
        note = "Generated fresh by scripts/ecocloud.ps1; 60 jobs per run."
    } | ConvertTo-Json -Depth 5
    [System.IO.File]::WriteAllText((Join-Path $OutDir "experiment_config.json"), $Config, [System.Text.UTF8Encoding]::new($false))

    Write-Host "Matched 60-job experiment validated. Raw results: $OutDir"
}

function Invoke-TimeAwareCV {
    Write-Host "`n=== TIME-AWARE CROSS-VALIDATION ==="

    $CVScript = Join-Path $RepoRoot "scripts\time_aware_cv.ps1"

    if (-not (Test-Path $CVScript)) {
        throw "Time-aware CV script not found: $CVScript"
    }

    & $CVScript

    if ($LASTEXITCODE -ne 0) {
        throw "Time-aware cross-validation failed."
    }
}

function Invoke-EcoCloudPlot {
    Write-Host "`n=== PLOTS ==="

    $PlotScript = Join-Path $RepoRoot "scripts\plot_forecast_guided_comparison.py"

    if (Test-Path $PlotScript) {
        & python $PlotScript

        if ($LASTEXITCODE -ne 0) {
            throw "Forecast plot generation failed."
        }
    }
    else {
        Write-Host "Forecast plot script not found; skipping forecast plots."
    }

    $PaperTracePlotScript = Join-Path $RepoRoot "scripts\plot_paper_trace_figures.py"
    if (Test-Path $PaperTracePlotScript) {
        & python $PaperTracePlotScript

        if ($LASTEXITCODE -ne 0) {
            throw "Paper trace figure generation failed."
        }
    }
}

function Invoke-EcoCloudClean {
    Write-Host "`n=== CLEAN BUILD & GENERATED OUTPUTS ==="

    if (Test-Path $BinDir) {
        Remove-Item -Recurse -Force $BinDir
    }
    Get-ChildItem -Path $RepoRoot -Filter "*.aux" -File | Remove-Item -Force
    Get-ChildItem -Path $RepoRoot -Filter "*.log" -File | Remove-Item -Force

    Write-Host "Cleaned build directory and temporary logs."
}

switch ($Action) {
    "clean" {
        Invoke-EcoCloudClean
    }

    "preprocess" {
        Invoke-EcoCloudPreprocess
    }

    "build" {
        Invoke-EcoCloudBuild
    }

    "test" {
        Invoke-EcoCloudTest
    }

    "audit" {
        Invoke-EcoCloudAudit
    }

    "sim" {
        Invoke-EcoCloudSimulations
    }

    "matched" {
        Invoke-Matched60JobExperiment
    }

    "cv" {
        Invoke-TimeAwareCV
    }

    "plot" {
        Invoke-EcoCloudPlot
    }

    "all" {
        Invoke-EcoCloudClean
        Invoke-EcoCloudPreprocess
        Invoke-EcoCloudBuild
        Invoke-EcoCloudTest
        Invoke-EcoCloudAudit
        Invoke-EcoCloudSimulations
        Invoke-Matched60JobExperiment
        Invoke-TimeAwareCV
        Invoke-EcoCloudPlot

        Write-Host "`n=== ECOCLOUD PIPELINE COMPLETED SUCCESSFULLY ===" -ForegroundColor Green
    }
}
