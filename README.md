# Cloud Task Scheduling: Min-Min Seeding & Bottleneck Repair

**CSE-574 Cloud Computing course project.** Benchmarking suite and paper evaluating **SA-DGSSA-BR** (Self-Adaptive Discrete GA-SSA with Min-Min Seeding and Bottleneck Repair) for cloud batch task-to-VM scheduling in **CloudSim Plus 8.5.7**.

## Contents
- `SchedulerExperiment.java` — Runs 3,000 matched-budget experiments across 10 schedulers (`SA_DGSSA_BR`, `GA_SSA_reconstruction`, `DECSO_inspired`, `EcoTaskOpt_inspired`, `MinMin`, `RoundRobin`, `GA`, `DiscreteSSA`, and ablations) under uniform and heavy-tailed workloads.
- `analyze.py` — Computes summary/paired bootstrap statistics and generates figures.
- `data/` & `figures/` — Recorded CSV results and plots.
- `*.pdf` — Accompanying paper.

## Quick Start
Requires Java 17+, CloudSim Plus 8.5.7, and Python (`pandas`, `numpy`, `matplotlib`).

