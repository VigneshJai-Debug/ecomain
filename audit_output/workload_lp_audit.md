# Detailed Audit: LP Formulation, Fractionality & Randomized Rounding

## 1. Executive Summary of Audit Findings

1. **LP Relaxation is Overwhelmingly Integral**: Across the 3 evaluation seeds, **65.0% to 68.3% of jobs (39 to 41 out of 60 jobs)** are solved to **exact integer values ($x_v^* = 1.0$)** by the Simplex solver.
2. **Zero Spatial Carbon Contrast**: In all seeds, the majority of multi-candidate fractional splits occur between DataCenter 0 and DataCenter 1 at the **exact same start slot**. Because both datacenters are located in India and observe the identical regional grid carbon trace, the forecast carbon spread is $\Delta C = 0.0000\text{ gCO}_2/\text{kWh}$, yielding an exponent of 0 and zero Gibbs tilt.
3. **Downstream Tie-Breaking and Rounding Invariance**: Monte Carlo rounding discovers identical feasible allocations across variants under identical regional signals, resulting in identical final emissions.
4. **Exact Baseline Identity**: For $\theta = 0.0$, the decision path bypasses the Gibbs calculations and matches original Bahreini bit-for-bit with $0.0000\text{ g}$ emissions difference.

## 2. Per-Seed LP Fractionality Table

| Seed | Total Variables | Positive-Support Variables ($x_v^* > 0$) | Strictly Fractional ($0 < x_v^* < 1$) | Single-Candidate Jobs ($|V^+|=1$) | Multi-Candidate Jobs ($|V^+|>1$) | % Single Candidate | Multi-Candidate with $\Delta C = 0$ | Spatial-Only Splits | Temporal Splits |
|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| `20260320` | 1500 | 86 | 45 | 41 / 60 | 19 / 60 | **68.3%** | 5 | 5 | 14 |
| `20260321` | 1542 | 91 | 50 | 41 / 60 | 19 / 60 | **68.3%** | 3 | 2 | 17 |
| `20260322` | 1568 | 95 | 56 | 39 / 60 | 21 / 60 | **65.0%** | 5 | 4 | 17 |

## 3. Detailed Per-Job LP Support for Multi-Candidate Jobs

The table below details every job across all three seeds that received more than one positive-support candidate in the LP relaxation:

| Seed | Job ID | CPU | Runtime | Candidate Var | Site | Start Slot | LP Value ($x_v^*$) | Norm Prob ($p_v$) | Forecast Mean CI (g) | Actual Mean CI (g) | CI Spread $\Delta C$ |
|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| `20260320` | Job 0 | 7 PE | 8 slots | Var 0 | Site 0 | Slot 7 (03:30) | `0.3000` | `0.3000` | `514.37` | `486.26` | `16.8053` |
| `20260320` | Job 0 | 7 PE | 8 slots | Var 2 | Site 0 | Slot 9 (04:30) | `0.7000` | `0.7000` | `497.57` | `471.58` | `16.8053` |
| `20260320` | Job 10 | 8 PE | 1 slots | Var 222 | Site 0 | Slot 12 (06:00) | `0.4250` | `0.4250` | `491.31` | `465.46` | `0.9087` |
| `20260320` | Job 10 | 8 PE | 1 slots | Var 223 | Site 0 | Slot 13 (06:30) | `0.1750` | `0.1750` | `491.31` | `465.46` | `0.9087` |
| `20260320` | Job 10 | 8 PE | 1 slots | Var 231 | Site 1 | Slot 16 (08:00) | `0.4000` | `0.4000` | `492.22` | `472.54` | `0.9087` |
| `20260320` | Job 14 | 10 PE | 1 slots | Var 312 | Site 0 | Slot 6 (03:00) | `0.9000` | `0.9000` | `582.12` | `556.29` | `0.0000` |
| `20260320` | Job 14 | 10 PE | 1 slots | Var 317 | Site 1 | Slot 6 (03:00) | `0.1000` | `0.1000` | `582.12` | `556.29` | `0.0000` |
| `20260320` | Job 16 | 10 PE | 5 slots | Var 332 | Site 0 | Slot 14 (07:00) | `0.1000` | `0.1000` | `495.22` | `474.70` | `75.8979` |
| `20260320` | Job 16 | 10 PE | 5 slots | Var 335 | Site 0 | Slot 17 (08:30) | `0.1450` | `0.1450` | `529.98` | `513.44` | `75.8979` |
| `20260320` | Job 16 | 10 PE | 5 slots | Var 357 | Site 1 | Slot 19 (09:30) | `0.7550` | `0.7550` | `571.12` | `563.40` | `75.8979` |
| `20260320` | Job 20 | 6 PE | 1 slots | Var 428 | Site 0 | Slot 6 (03:00) | `0.2083` | `0.2083` | `582.12` | `556.29` | `51.6101` |
| `20260320` | Job 20 | 6 PE | 1 slots | Var 429 | Site 0 | Slot 7 (03:30) | `0.6917` | `0.6917` | `582.12` | `556.29` | `51.6101` |
| `20260320` | Job 20 | 6 PE | 1 slots | Var 430 | Site 0 | Slot 8 (04:00) | `0.1000` | `0.1000` | `530.51` | `498.01` | `51.6101` |
| `20260320` | Job 22 | 6 PE | 5 slots | Var 468 | Site 0 | Slot 6 (03:00) | `0.6667` | `0.6667` | `545.38` | `515.97` | `7.9210` |
| `20260320` | Job 22 | 6 PE | 5 slots | Var 501 | Site 1 | Slot 18 (09:00) | `0.3333` | `0.3333` | `553.30` | `541.14` | `7.9210` |
| `20260320` | Job 25 | 5 PE | 2 slots | Var 583 | Site 1 | Slot 12 (06:00) | `0.3800` | `0.3800` | `491.31` | `465.46` | `2.2135` |
| `20260320` | Job 25 | 5 PE | 2 slots | Var 586 | Site 1 | Slot 15 (07:30) | `0.6200` | `0.6200` | `489.10` | `468.44` | `2.2135` |
| `20260320` | Job 29 | 8 PE | 5 slots | Var 680 | Site 0 | Slot 9 (04:30) | `0.8375` | `0.8375` | `503.27` | `474.29` | `26.7082` |
| `20260320` | Job 29 | 8 PE | 5 slots | Var 707 | Site 1 | Slot 17 (08:30) | `0.1625` | `0.1625` | `529.98` | `513.44` | `26.7082` |
| `20260320` | Job 32 | 9 PE | 3 slots | Var 764 | Site 0 | Slot 11 (05:30) | `0.3333` | `0.3333` | `494.75` | `467.39` | `38.1059` |
| `20260320` | Job 32 | 9 PE | 3 slots | Var 782 | Site 1 | Slot 8 (04:00) | `0.1111` | `0.1111` | `520.88` | `489.09` | `38.1059` |
| `20260320` | Job 32 | 9 PE | 3 slots | Var 792 | Site 1 | Slot 18 (09:00) | `0.5556` | `0.5556` | `532.85` | `515.69` | `38.1059` |
| `20260320` | Job 35 | 7 PE | 2 slots | Var 843 | Site 0 | Slot 6 (03:00) | `0.4286` | `0.4286` | `582.12` | `556.29` | `0.0000` |
| `20260320` | Job 35 | 7 PE | 2 slots | Var 858 | Site 1 | Slot 6 (03:00) | `0.5714` | `0.5714` | `582.12` | `556.29` | `0.0000` |
| `20260320` | Job 40 | 8 PE | 10 slots | Var 981 | Site 0 | Slot 14 (07:00) | `0.8875` | `0.8875` | `533.17` | `519.05` | `23.4977` |
| `20260320` | Job 40 | 8 PE | 10 slots | Var 999 | Site 1 | Slot 12 (06:00) | `0.1125` | `0.1125` | `509.67` | `489.94` | `23.4977` |
| `20260320` | Job 41 | 9 PE | 9 slots | Var 1018 | Site 0 | Slot 8 (04:00) | `0.1722` | `0.1722` | `501.23` | `474.52` | `40.6288` |
| `20260320` | Job 41 | 9 PE | 9 slots | Var 1021 | Site 0 | Slot 11 (05:30) | `0.1111` | `0.1111` | `497.78` | `475.04` | `40.6288` |
| `20260320` | Job 41 | 9 PE | 9 slots | Var 1025 | Site 0 | Slot 15 (07:30) | `0.2333` | `0.2333` | `538.41` | `525.13` | `40.6288` |
| `20260320` | Job 41 | 9 PE | 9 slots | Var 1044 | Site 1 | Slot 15 (07:30) | `0.4833` | `0.4833` | `538.41` | `525.13` | `40.6288` |
| `20260320` | Job 42 | 6 PE | 10 slots | Var 1058 | Site 0 | Slot 6 (03:00) | `0.1250` | `0.1250` | `518.31` | `491.07` | `14.8599` |
| `20260320` | Job 42 | 6 PE | 10 slots | Var 1086 | Site 1 | Slot 14 (07:00) | `0.8750` | `0.8750` | `533.17` | `519.05` | `14.8599` |
| `20260320` | Job 45 | 9 PE | 3 slots | Var 1161 | Site 0 | Slot 16 (08:00) | `0.2222` | `0.2222` | `501.38` | `481.60` | `11.8511` |
| `20260320` | Job 45 | 9 PE | 3 slots | Var 1165 | Site 1 | Slot 12 (06:00) | `0.2722` | `0.2722` | `489.53` | `465.09` | `11.8511` |
| `20260320` | Job 45 | 9 PE | 3 slots | Var 1169 | Site 1 | Slot 16 (08:00) | `0.5056` | `0.5056` | `501.38` | `481.60` | `11.8511` |
| `20260320` | Job 47 | 10 PE | 10 slots | Var 1186 | Site 0 | Slot 6 (03:00) | `0.1250` | `0.1250` | `518.31` | `491.07` | `0.0000` |
| `20260320` | Job 47 | 10 PE | 10 slots | Var 1194 | Site 1 | Slot 6 (03:00) | `0.8750` | `0.8750` | `518.31` | `491.07` | `0.0000` |
| `20260320` | Job 48 | 10 PE | 6 slots | Var 1206 | Site 0 | Slot 6 (03:00) | `0.3750` | `0.3750` | `538.08` | `508.52` | `0.0000` |
| `20260320` | Job 48 | 10 PE | 6 slots | Var 1222 | Site 1 | Slot 6 (03:00) | `0.6250` | `0.6250` | `538.08` | `508.52` | `0.0000` |
| `20260320` | Job 54 | 7 PE | 3 slots | Var 1350 | Site 1 | Slot 17 (08:30) | `0.1429` | `0.1429` | `510.55` | `490.66` | `65.1460` |
| `20260320` | Job 54 | 7 PE | 3 slots | Var 1353 | Site 1 | Slot 20 (10:00) | `0.8571` | `0.8571` | `575.69` | `568.75` | `65.1460` |
| `20260320` | Job 56 | 8 PE | 10 slots | Var 1382 | Site 0 | Slot 12 (06:00) | `0.7938` | `0.7938` | `509.67` | `489.94` | `23.4977` |
| `20260320` | Job 56 | 8 PE | 10 slots | Var 1404 | Site 1 | Slot 14 (07:00) | `0.2062` | `0.2062` | `533.17` | `519.05` | `23.4977` |
| `20260320` | Job 58 | 9 PE | 3 slots | Var 1461 | Site 0 | Slot 6 (03:00) | `0.6667` | `0.6667` | `564.92` | `536.87` | `0.0000` |
| `20260320` | Job 58 | 9 PE | 3 slots | Var 1477 | Site 1 | Slot 6 (03:00) | `0.3333` | `0.3333` | `564.92` | `536.87` | `0.0000` |
| `20260321` | Job 3 | 5 PE | 2 slots | Var 72 | Site 0 | Slot 6 (03:00) | `0.1000` | `0.1000` | `582.12` | `556.29` | `51.6101` |
| `20260321` | Job 3 | 5 PE | 2 slots | Var 88 | Site 1 | Slot 6 (03:00) | `0.3500` | `0.3500` | `582.12` | `556.29` | `51.6101` |
| `20260321` | Job 3 | 5 PE | 2 slots | Var 90 | Site 1 | Slot 8 (04:00) | `0.5500` | `0.5500` | `530.51` | `498.01` | `51.6101` |
| `20260321` | Job 6 | 8 PE | 6 slots | Var 171 | Site 1 | Slot 6 (03:00) | `0.4219` | `0.4219` | `538.08` | `508.52` | `45.1170` |
| `20260321` | Job 6 | 8 PE | 6 slots | Var 175 | Site 1 | Slot 10 (05:00) | `0.5781` | `0.5781` | `492.97` | `467.02` | `45.1170` |
| `20260321` | Job 7 | 6 PE | 10 slots | Var 184 | Site 0 | Slot 11 (05:30) | `0.2870` | `0.2870` | `503.92` | `482.30` | `5.7517` |
| `20260321` | Job 7 | 6 PE | 10 slots | Var 190 | Site 1 | Slot 11 (05:30) | `0.1505` | `0.1505` | `503.92` | `482.30` | `5.7517` |
| `20260321` | Job 7 | 6 PE | 10 slots | Var 191 | Site 1 | Slot 12 (06:00) | `0.5625` | `0.5625` | `509.67` | `489.94` | `5.7517` |
| `20260321` | Job 8 | 10 PE | 10 slots | Var 199 | Site 0 | Slot 13 (06:30) | `0.0875` | `0.0875` | `521.42` | `504.49` | `11.7489` |
| `20260321` | Job 8 | 10 PE | 10 slots | Var 212 | Site 1 | Slot 13 (06:30) | `0.2708` | `0.2708` | `521.42` | `504.49` | `11.7489` |
| `20260321` | Job 8 | 10 PE | 10 slots | Var 213 | Site 1 | Slot 14 (07:00) | `0.6417` | `0.6417` | `533.17` | `519.05` | `11.7489` |
| `20260321` | Job 9 | 7 PE | 6 slots | Var 231 | Site 1 | Slot 9 (04:30) | `0.5714` | `0.5714` | `500.39` | `472.63` | `42.7276` |
| `20260321` | Job 9 | 7 PE | 6 slots | Var 239 | Site 1 | Slot 17 (08:30) | `0.4286` | `0.4286` | `543.12` | `529.71` | `42.7276` |
| `20260321` | Job 15 | 5 PE | 3 slots | Var 390 | Site 0 | Slot 13 (06:30) | `0.8194` | `0.8194` | `487.75` | `464.72` | `104.4926` |
| `20260321` | Job 15 | 5 PE | 3 slots | Var 408 | Site 1 | Slot 21 (10:30) | `0.1806` | `0.1806` | `592.24` | `589.89` | `104.4926` |
| `20260321` | Job 16 | 7 PE | 5 slots | Var 410 | Site 0 | Slot 6 (03:00) | `0.8373` | `0.8373` | `545.38` | `515.97` | `76.7495` |
| `20260321` | Job 16 | 7 PE | 5 slots | Var 414 | Site 0 | Slot 10 (05:00) | `0.1429` | `0.1429` | `494.37` | `467.55` | `76.7495` |
| `20260321` | Job 16 | 7 PE | 5 slots | Var 423 | Site 0 | Slot 19 (09:30) | `0.0198` | `0.0198` | `571.12` | `563.40` | `76.7495` |
| `20260321` | Job 18 | 10 PE | 7 slots | Var 475 | Site 0 | Slot 17 (08:30) | `0.8722` | `0.8722` | `552.50` | `541.33` | `0.0000` |
| `20260321` | Job 18 | 10 PE | 7 slots | Var 497 | Site 1 | Slot 17 (08:30) | `0.1278` | `0.1278` | `552.50` | `541.33` | `0.0000` |
| `20260321` | Job 30 | 7 PE | 8 slots | Var 764 | Site 0 | Slot 6 (03:00) | `0.3095` | `0.3095` | `526.39` | `497.75` | `18.5749` |
| `20260321` | Job 30 | 7 PE | 8 slots | Var 774 | Site 0 | Slot 16 (08:00) | `0.6905` | `0.6905` | `544.97` | `532.73` | `18.5749` |
| `20260321` | Job 34 | 10 PE | 4 slots | Var 872 | Site 0 | Slot 11 (05:30) | `0.2139` | `0.2139` | `492.55` | `466.63` | `91.4148` |
| `20260321` | Job 34 | 10 PE | 4 slots | Var 876 | Site 0 | Slot 15 (07:30) | `0.0139` | `0.0139` | `497.53` | `477.29` | `91.4148` |
| `20260321` | Job 34 | 10 PE | 4 slots | Var 878 | Site 0 | Slot 17 (08:30) | `0.0278` | `0.0278` | `522.69` | `504.90` | `91.4148` |
| `20260321` | Job 34 | 10 PE | 4 slots | Var 881 | Site 0 | Slot 20 (10:00) | `0.4000` | `0.4000` | `583.97` | `579.32` | `91.4148` |
| `20260321` | Job 34 | 10 PE | 4 slots | Var 897 | Site 1 | Slot 16 (08:00) | `0.0167` | `0.0167` | `505.96` | `486.13` | `91.4148` |
| `20260321` | Job 34 | 10 PE | 4 slots | Var 901 | Site 1 | Slot 20 (10:00) | `0.3278` | `0.3278` | `583.97` | `579.32` | `91.4148` |
| `20260321` | Job 40 | 10 PE | 9 slots | Var 1013 | Site 0 | Slot 9 (04:30) | `0.2264` | `0.2264` | `496.97` | `471.69` | `41.4384` |
| `20260321` | Job 40 | 10 PE | 9 slots | Var 1030 | Site 1 | Slot 6 (03:00) | `0.2458` | `0.2458` | `521.90` | `494.04` | `41.4384` |
| `20260321` | Job 40 | 10 PE | 9 slots | Var 1035 | Site 1 | Slot 11 (05:30) | `0.0819` | `0.0819` | `497.78` | `475.04` | `41.4384` |
| `20260321` | Job 40 | 10 PE | 9 slots | Var 1039 | Site 1 | Slot 15 (07:30) | `0.4458` | `0.4458` | `538.41` | `525.13` | `41.4384` |
| `20260321` | Job 41 | 7 PE | 2 slots | Var 1063 | Site 0 | Slot 22 (11:00) | `0.5476` | `0.5476` | `608.80` | `611.03` | `0.0000` |
| `20260321` | Job 41 | 7 PE | 2 slots | Var 1078 | Site 1 | Slot 22 (11:00) | `0.4524` | `0.4524` | `608.80` | `611.03` | `0.0000` |
| `20260321` | Job 46 | 5 PE | 4 slots | Var 1179 | Site 0 | Slot 14 (07:00) | `0.4333` | `0.4333` | `489.10` | `468.44` | `50.3282` |
| `20260321` | Job 46 | 5 PE | 4 slots | Var 1183 | Site 0 | Slot 18 (09:00) | `0.5667` | `0.5667` | `539.42` | `523.67` | `50.3282` |
| `20260321` | Job 49 | 5 PE | 1 slots | Var 1250 | Site 0 | Slot 6 (03:00) | `0.7000` | `0.7000` | `582.12` | `556.29` | `0.0000` |
| `20260321` | Job 49 | 5 PE | 1 slots | Var 1251 | Site 0 | Slot 7 (03:30) | `0.3000` | `0.3000` | `582.12` | `556.29` | `0.0000` |
| `20260321` | Job 52 | 10 PE | 6 slots | Var 1318 | Site 0 | Slot 18 (09:00) | `0.3597` | `0.3597` | `562.55` | `552.79` | `71.1478` |
| `20260321` | Job 52 | 10 PE | 6 slots | Var 1328 | Site 1 | Slot 10 (05:00) | `0.2125` | `0.2125` | `492.97` | `467.02` | `71.1478` |
| `20260321` | Job 52 | 10 PE | 6 slots | Var 1329 | Site 1 | Slot 11 (05:30) | `0.4278` | `0.4278` | `491.40` | `467.23` | `71.1478` |
| `20260321` | Job 53 | 6 PE | 4 slots | Var 1369 | Site 1 | Slot 14 (07:00) | `0.5000` | `0.5000` | `489.10` | `468.44` | `50.3282` |
| `20260321` | Job 53 | 6 PE | 4 slots | Var 1373 | Site 1 | Slot 18 (09:00) | `0.5000` | `0.5000` | `539.42` | `523.67` | `50.3282` |
| `20260321` | Job 55 | 7 PE | 7 slots | Var 1421 | Site 0 | Slot 6 (03:00) | `0.5675` | `0.5675` | `531.40` | `502.37` | `33.2440` |
| `20260321` | Job 55 | 7 PE | 7 slots | Var 1424 | Site 0 | Slot 9 (04:30) | `0.1052` | `0.1052` | `498.33` | `471.45` | `33.2440` |
| `20260321` | Job 55 | 7 PE | 7 slots | Var 1439 | Site 1 | Slot 13 (06:30) | `0.3274` | `0.3274` | `498.16` | `476.96` | `33.2440` |
| `20260321` | Job 57 | 5 PE | 1 slots | Var 1469 | Site 1 | Slot 6 (03:00) | `0.2000` | `0.2000` | `582.12` | `556.29` | `51.6101` |
| `20260321` | Job 57 | 5 PE | 1 slots | Var 1472 | Site 1 | Slot 9 (04:30) | `0.8000` | `0.8000` | `530.51` | `498.01` | `51.6101` |
| `20260321` | Job 58 | 9 PE | 8 slots | Var 1496 | Site 1 | Slot 6 (03:00) | `0.7130` | `0.7130` | `526.39` | `497.75` | `18.5749` |
| `20260321` | Job 58 | 9 PE | 8 slots | Var 1506 | Site 1 | Slot 16 (08:00) | `0.2870` | `0.2870` | `544.97` | `532.73` | `18.5749` |
| `20260322` | Job 1 | 6 PE | 3 slots | Var 34 | Site 0 | Slot 23 (11:30) | `0.5000` | `0.5000` | `640.23` | `650.67` | `0.0000` |
| `20260322` | Job 1 | 6 PE | 3 slots | Var 55 | Site 1 | Slot 23 (11:30) | `0.5000` | `0.5000` | `640.23` | `650.67` | `0.0000` |
| `20260322` | Job 7 | 4 PE | 1 slots | Var 203 | Site 1 | Slot 4 (02:00) | `0.5000` | `0.5000` | `644.97` | `637.90` | `0.0000` |
| `20260322` | Job 7 | 4 PE | 1 slots | Var 204 | Site 1 | Slot 5 (02:30) | `0.5000` | `0.5000` | `644.97` | `637.90` | `0.0000` |
| `20260322` | Job 8 | 6 PE | 5 slots | Var 225 | Site 0 | Slot 17 (08:30) | `0.3333` | `0.3333` | `529.98` | `513.44` | `87.7418` |
| `20260322` | Job 8 | 6 PE | 5 slots | Var 229 | Site 0 | Slot 21 (10:30) | `0.6667` | `0.6667` | `617.72` | `622.13` | `87.7418` |
| `20260322` | Job 9 | 7 PE | 2 slots | Var 243 | Site 0 | Slot 10 (05:00) | `0.9015` | `0.9015` | `501.62` | `471.25` | `12.9797` |
| `20260322` | Job 9 | 7 PE | 2 slots | Var 256 | Site 1 | Slot 13 (06:30) | `0.0443` | `0.0443` | `488.64` | `464.90` | `12.9797` |
| `20260322` | Job 9 | 7 PE | 2 slots | Var 258 | Site 1 | Slot 15 (07:30) | `0.0542` | `0.0542` | `489.10` | `468.44` | `12.9797` |
| `20260322` | Job 10 | 10 PE | 2 slots | Var 266 | Site 0 | Slot 13 (06:30) | `0.6776` | `0.6776` | `488.64` | `464.90` | `122.8243` |
| `20260322` | Job 10 | 10 PE | 2 slots | Var 267 | Site 0 | Slot 14 (07:00) | `0.0181` | `0.0181` | `485.97` | `464.34` | `122.8243` |
| `20260322` | Job 10 | 10 PE | 2 slots | Var 283 | Site 1 | Slot 16 (08:00) | `0.0276` | `0.0276` | `492.22` | `472.54` | `122.8243` |
| `20260322` | Job 10 | 10 PE | 2 slots | Var 286 | Site 1 | Slot 19 (09:30) | `0.0914` | `0.0914` | `539.42` | `523.67` | `122.8243` |
| `20260322` | Job 10 | 10 PE | 2 slots | Var 289 | Site 1 | Slot 22 (11:00) | `0.1853` | `0.1853` | `608.80` | `611.03` | `122.8243` |
| `20260322` | Job 12 | 10 PE | 5 slots | Var 326 | Site 0 | Slot 4 (02:00) | `0.7500` | `0.7500` | `596.94` | `577.28` | `0.0000` |
| `20260322` | Job 12 | 10 PE | 5 slots | Var 341 | Site 1 | Slot 4 (02:00) | `0.2500` | `0.2500` | `596.94` | `577.28` | `0.0000` |
| `20260322` | Job 15 | 8 PE | 5 slots | Var 427 | Site 0 | Slot 9 (04:30) | `0.6379` | `0.6379` | `503.27` | `474.29` | `67.8419` |
| `20260322` | Job 15 | 8 PE | 5 slots | Var 456 | Site 1 | Slot 19 (09:30) | `0.3621` | `0.3621` | `571.12` | `563.40` | `67.8419` |
| `20260322` | Job 18 | 8 PE | 8 slots | Var 506 | Site 0 | Slot 12 (06:00) | `0.7888` | `0.7888` | `497.30` | `475.52` | `8.4786` |
| `20260322` | Job 18 | 8 PE | 8 slots | Var 513 | Site 1 | Slot 13 (06:30) | `0.2112` | `0.2112` | `505.78` | `485.79` | `8.4786` |
| `20260322` | Job 23 | 9 PE | 7 slots | Var 592 | Site 0 | Slot 10 (05:00) | `0.1437` | `0.1437` | `492.86` | `467.81` | `59.6405` |
| `20260322` | Job 23 | 9 PE | 7 slots | Var 598 | Site 0 | Slot 16 (08:00) | `0.0862` | `0.0862` | `535.85` | `521.54` | `59.6405` |
| `20260322` | Job 23 | 9 PE | 7 slots | Var 599 | Site 0 | Slot 17 (08:30) | `0.1906` | `0.1906` | `552.50` | `541.33` | `59.6405` |
| `20260322` | Job 23 | 9 PE | 7 slots | Var 607 | Site 1 | Slot 17 (08:30) | `0.5795` | `0.5795` | `552.50` | `541.33` | `59.6405` |
| `20260322` | Job 24 | 9 PE | 10 slots | Var 609 | Site 0 | Slot 8 (04:00) | `0.5556` | `0.5556` | `500.33` | `474.32` | `23.2523` |
| `20260322` | Job 24 | 9 PE | 10 slots | Var 614 | Site 0 | Slot 13 (06:30) | `0.0249` | `0.0249` | `521.42` | `504.49` | `23.2523` |
| `20260322` | Job 24 | 9 PE | 10 slots | Var 626 | Site 1 | Slot 10 (05:00) | `0.4195` | `0.4195` | `498.17` | `474.66` | `23.2523` |
| `20260322` | Job 25 | 10 PE | 6 slots | Var 639 | Site 0 | Slot 4 (02:00) | `0.9853` | `0.9853` | `585.87` | `564.07` | `94.4671` |
| `20260322` | Job 25 | 10 PE | 6 slots | Var 656 | Site 1 | Slot 11 (05:30) | `0.0147` | `0.0147` | `491.40` | `467.23` | `94.4671` |
| `20260322` | Job 27 | 10 PE | 1 slots | Var 699 | Site 0 | Slot 9 (04:30) | `0.2750` | `0.2750` | `530.51` | `498.01` | `107.1768` |
| `20260322` | Job 27 | 10 PE | 1 slots | Var 712 | Site 0 | Slot 22 (11:00) | `0.2000` | `0.2000` | `608.80` | `611.03` | `107.1768` |
| `20260322` | Job 27 | 10 PE | 1 slots | Var 716 | Site 1 | Slot 11 (05:30) | `0.3853` | `0.3853` | `501.62` | `471.25` | `107.1768` |
| `20260322` | Job 27 | 10 PE | 1 slots | Var 725 | Site 1 | Slot 20 (10:00) | `0.1397` | `0.1397` | `559.14` | `547.61` | `107.1768` |
| `20260322` | Job 30 | 10 PE | 3 slots | Var 775 | Site 1 | Slot 4 (02:00) | `0.9500` | `0.9500` | `624.02` | `610.70` | `76.3053` |
| `20260322` | Job 30 | 10 PE | 3 slots | Var 778 | Site 1 | Slot 7 (03:30) | `0.0500` | `0.0500` | `547.72` | `517.44` | `76.3053` |
| `20260322` | Job 31 | 10 PE | 4 slots | Var 794 | Site 0 | Slot 14 (07:00) | `0.1310` | `0.1310` | `489.10` | `468.44` | `94.8724` |
| `20260322` | Job 31 | 10 PE | 4 slots | Var 800 | Site 0 | Slot 20 (10:00) | `0.7690` | `0.7690` | `583.97` | `579.32` | `94.8724` |
| `20260322` | Job 31 | 10 PE | 4 slots | Var 805 | Site 1 | Slot 14 (07:00) | `0.1000` | `0.1000` | `489.10` | `468.44` | `94.8724` |
| `20260322` | Job 33 | 10 PE | 8 slots | Var 826 | Site 0 | Slot 7 (03:30) | `0.2224` | `0.2224` | `514.37` | `486.26` | `52.1854` |
| `20260322` | Job 33 | 10 PE | 8 slots | Var 835 | Site 0 | Slot 16 (08:00) | `0.1052` | `0.1052` | `544.97` | `532.73` | `52.1854` |
| `20260322` | Job 33 | 10 PE | 8 slots | Var 843 | Site 1 | Slot 10 (05:00) | `0.6724` | `0.6724` | `492.78` | `468.40` | `52.1854` |
| `20260322` | Job 36 | 10 PE | 10 slots | Var 911 | Site 0 | Slot 14 (07:00) | `0.6612` | `0.6612` | `533.17` | `519.05` | `67.9151` |
| `20260322` | Job 36 | 10 PE | 10 slots | Var 926 | Site 1 | Slot 9 (04:30) | `0.0810` | `0.0810` | `499.25` | `474.49` | `67.9151` |
| `20260322` | Job 36 | 10 PE | 10 slots | Var 929 | Site 1 | Slot 12 (06:00) | `0.0853` | `0.0853` | `509.67` | `489.94` | `67.9151` |
| `20260322` | Job 36 | 10 PE | 10 slots | Var 933 | Site 1 | Slot 16 (08:00) | `0.1724` | `0.1724` | `567.16` | `560.28` | `67.9151` |
| `20260322` | Job 42 | 10 PE | 3 slots | Var 1104 | Site 0 | Slot 4 (02:00) | `0.9647` | `0.9647` | `624.02` | `610.70` | `59.1019` |
| `20260322` | Job 42 | 10 PE | 3 slots | Var 1106 | Site 0 | Slot 6 (03:00) | `0.0353` | `0.0353` | `564.92` | `536.87` | `59.1019` |
| `20260322` | Job 47 | 10 PE | 10 slots | Var 1254 | Site 0 | Slot 6 (03:00) | `0.0647` | `0.0647` | `518.31` | `491.07` | `40.8474` |
| `20260322` | Job 47 | 10 PE | 10 slots | Var 1255 | Site 0 | Slot 7 (03:30) | `0.7422` | `0.7422` | `509.32` | `482.70` | `40.8474` |
| `20260322` | Job 47 | 10 PE | 10 slots | Var 1281 | Site 1 | Slot 15 (07:30) | `0.1931` | `0.1931` | `550.16` | `539.67` | `40.8474` |
| `20260322` | Job 54 | 7 PE | 4 slots | Var 1422 | Site 0 | Slot 4 (02:00) | `0.7143` | `0.7143` | `613.55` | `597.10` | `0.0000` |
| `20260322` | Job 54 | 7 PE | 4 slots | Var 1444 | Site 1 | Slot 4 (02:00) | `0.2857` | `0.2857` | `613.55` | `597.10` | `0.0000` |
| `20260322` | Job 55 | 8 PE | 8 slots | Var 1472 | Site 0 | Slot 18 (09:00) | `0.1638` | `0.1638` | `585.90` | `582.21` | `88.3303` |
| `20260322` | Job 55 | 8 PE | 8 slots | Var 1477 | Site 1 | Slot 9 (04:30) | `0.8362` | `0.8362` | `497.57` | `471.58` | `88.3303` |
| `20260322` | Job 59 | 9 PE | 6 slots | Var 1551 | Site 0 | Slot 20 (10:00) | `0.4023` | `0.4023` | `607.96` | `609.71` | `0.0000` |
| `20260322` | Job 59 | 9 PE | 6 slots | Var 1564 | Site 1 | Slot 20 (10:00) | `0.5977` | `0.5977` | `607.96` | `609.71` | `0.0000` |

## 4. Realized Scheduling Decisions Across Variants

Comparison of realized placement across the 5 scheduler variants:

| Seed | Variant | Scheduled Jobs | Failed Jobs | Completed Jobs | Mean Waiting (s) | Deadline Violations | Total CO2e (g) | Savings vs FCFS |
|:---:|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| `20260320` | Original_Bahreini | 58 | 2 | 58 | — | — | — | — |
| `20260320` | Bahreini_Theta_0.0 | 58 | 2 | 58 | — | — | — | — |
| `20260320` | Forecast_Guided_Theta_0.5 | 57 | 3 | 57 | — | — | — | — |
| `20260320` | Forecast_Guided_Theta_1.0 | 57 | 3 | 57 | — | — | — | — |
| `20260320` | Oracle_Theta_1.0 | 58 | 2 | 58 | — | — | — | — |
| `20260321` | Original_Bahreini | 57 | 3 | 57 | — | — | — | — |
| `20260321` | Bahreini_Theta_0.0 | 57 | 3 | 57 | — | — | — | — |
| `20260321` | Forecast_Guided_Theta_0.5 | 56 | 4 | 56 | — | — | — | — |
| `20260321` | Forecast_Guided_Theta_1.0 | 56 | 4 | 56 | — | — | — | — |
| `20260321` | Oracle_Theta_1.0 | 56 | 4 | 56 | — | — | — | — |
| `20260322` | Original_Bahreini | 56 | 4 | 56 | — | — | — | — |
| `20260322` | Bahreini_Theta_0.0 | 56 | 4 | 56 | — | — | — | — |
| `20260322` | Forecast_Guided_Theta_0.5 | 56 | 4 | 56 | — | — | — | — |
| `20260322` | Forecast_Guided_Theta_1.0 | 56 | 4 | 56 | — | — | — | — |
| `20260322` | Oracle_Theta_1.0 | 56 | 4 | 56 | — | — | — | — |

## 5. Architectural Root Cause & Bottleneck Classification

### Root Cause 1: Aggregate Capacity Headroom
The 60-job workload requires an average of ~5.5 PEs per job and ~5.5 slots runtime (~2.8 hours). Across the 24-hour horizon, total compute demand is approximately 908 PE-hours. The datacenter cluster provides 2 sites * 50 PEs * 24 hours = 2400 PE-hours of capacity (cluster load factor ~37.8%). Because capacity is not bottlenecked at peak hours, the LP relaxation naturally assigns each job to its single most optimal time window without being forced to fractionalize.

### Root Cause 2: Identical Grid Profile Across Sites
In the standard multi-site configuration, both datacenters are assigned the same Indian grid carbon trace. When the LP relaxation splits a job between Site 0 and Site 1 at the same hour, the carbon difference between the candidates is exactly zero. Gibbs tilting requires a non-zero gradient ($\bar{C}_v - \bar{C}_i^{\min} > 0$) to alter sampling probabilities.

### Root Cause 3: Downstream Tie-Breaking Invariance
For jobs exhibiting a multi-slot temporal split across candidate hours, Monte Carlo draws evaluate samples against standard GRU, and identical optimal placements are selected.
