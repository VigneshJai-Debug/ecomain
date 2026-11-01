# Workload and Simulation Configuration Audit

## 1. Trace and Horizon Specification
- **Region**: India (Central Electricity Authority - CEA hourly trace)
- **Trace Path**: `data/india/india_carbon_hourly_utc_aligned.csv`
- **Simulation Start Time**: Hour 432 (`2026-09-07 00:00:00 IST`)
- **Horizon**: 48 slots of 30 minutes (24.0 hours)
- **Slot Duration**: 1800 seconds (30 minutes)

## 2. Infrastructure Specification
- **Datacenter Sites**: 2 logical sites
- **Capacity**: 50 PEs per site (100 PEs concurrent aggregate capacity)
- **Processing Speed**: 1000 MIPS per PE
- **VM Provisioning**: 1 VM per site, 50 PEs, `CloudletSchedulerSpaceShared`
- **Power Proxy Model**: 150.0 W active VM power model

## 3. Workload Generation Parameters
- **Job Count**: 60 non-preemptive batch jobs
- **Seeds**: `20260320L`, `20260321L`, `20260322L`
- **CPU Demand**: Uniform discrete distribution $[1, 10]$ PEs
- **Runtime**: Uniform discrete distribution $[1, 10]$ slots (0.5 to 5.0 hours)
- **Release Slot**: Uniform discrete distribution $[0, 7]$ slots (0.0 to 3.5 hours into day)
- **Slack**: $2 + [0, 12]$ slots (1.0 to 7.0 hours deferral slack)
- **Deadline Slot**: $\min(\text{release} + \text{runtime} + \text{slack}, 47)$

## 4. Forecaster Configuration
- **Type**: Causal 24-Hour Seasonal-Naive with Multi-Level Fallback
- **Training Cutoff**: Hour 431 (first 18 days, zero future leakage)
- **Target Window**: Hours 432–455 (Day 19, 100% complete records)
- **Characteristic Spread ($\sigma_{\text{trace}}$)**: $94.67\text{ gCO}_2/\text{kWh}$
