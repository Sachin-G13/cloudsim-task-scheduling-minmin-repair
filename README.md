# Cloud Task Scheduling: Min-Min Seeding & Bottleneck Repair

**CSE-574 Cloud Computing course project.** Proposes **SA-DGSSA-BR**, a Min-Min–seeded GA–SSA hybrid with bottleneck repair for cloud batch task scheduling in **CloudSim Plus 8.5.7**. Across 50–500 tasks (240 seeds), it cuts makespan by **8.15%** (GA–SSA), **8.73%** (EcoTaskOpt), and **10.36%** (DECSO), with **6.52%** from bottleneck repair.

## Contents
- `SchedulerExperiment.java` — 3,000 matched-budget runs across 10 schedulers and two workloads.
- `analyze.py` — Paired bootstrap statistics and figure generation.
- `data/`, `figures/`, `*.pdf` — Recorded CSVs, plots, and paper.

## Quick Start
Requires Java 17+, CloudSim Plus 8.5.7, and Python (`pandas`, `numpy`, `matplotlib`).
