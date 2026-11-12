"""Summarize paired CloudSim runs and draw the second-evaluation figures.

Requires pandas, numpy and matplotlib. Run: python analyze.py results
"""
from pathlib import Path
import sys
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "results")
DATA = pd.read_csv(ROOT / "runs.csv")
PROPOSED = "SA_DGSSA_BR"
COMPARATORS = ["GA_SSA_reconstruction", "DECSO_inspired", "EcoTaskOpt_inspired", "MinMin", "NoRepair", "NoAdapt"]


def bootstrap_mean_ci(values, seed=20261025, repeats=10000):
    x = np.asarray(values, dtype=float)
    rng = np.random.default_rng(seed)
    indices = rng.integers(0, len(x), size=(repeats, len(x)))
    draws = x[indices].mean(axis=1)
    return (float(x.mean()), float(np.quantile(draws, .025)), float(np.quantile(draws, .975)))


summary = (DATA.groupby(["workload", "tasks", "method"], as_index=False)
           .agg(seeds=("seed", "nunique"), makespan_median_s=("makespan_s", "median"),
                makespan_mean_s=("makespan_s", "mean"), tardiness_median=("tardiness", "median"),
                miss_rate_median=("deadline_miss_rate", "median"), load_cv_median=("load_cv", "median"),
                optimizer_median_ms=("optimizer_ms", "median")))
summary.to_csv(ROOT / "summary.csv", index=False)

rows = []
for (workload, tasks), group in DATA.groupby(["workload", "tasks"]):
    pivot = group.pivot(index="seed", columns="method", values="makespan_s")
    for baseline in COMPARATORS:
        improvement = 100 * (pivot[baseline] - pivot[PROPOSED]) / pivot[baseline]
        mean, low, high = bootstrap_mean_ci(improvement)
        rows.append(dict(workload=workload, tasks=tasks, baseline=baseline,
                         paired_seeds=len(improvement), mean_improvement_pct=mean,
                         bootstrap_95_low_pct=low, bootstrap_95_high_pct=high,
                         proposed_wins=int((improvement > 0).sum()),
                         proposed_ties=int((improvement == 0).sum()),
                         baseline_wins=int((improvement < 0).sum())))
for group_name, sizes in [("all_10", [10]), ("all_50_to_500", [50, 100, 200, 500])]:
    subset = DATA[DATA.tasks.isin(sizes)]
    pivot = subset.pivot(index=["workload", "tasks", "seed"], columns="method", values="makespan_s")
    for baseline in COMPARATORS:
        improvement = 100 * (pivot[baseline] - pivot[PROPOSED]) / pivot[baseline]
        mean, low, high = bootstrap_mean_ci(improvement)
        rows.append(dict(workload=group_name, tasks=-1, baseline=baseline,
                         paired_seeds=len(improvement), mean_improvement_pct=mean,
                         bootstrap_95_low_pct=low, bootstrap_95_high_pct=high,
                         proposed_wins=int((improvement > 0).sum()),
                         proposed_ties=int((improvement == 0).sum()),
                         baseline_wins=int((improvement < 0).sum())))
paired = pd.DataFrame(rows)
paired.to_csv(ROOT / "paired_improvements.csv", index=False)

styles = {
    "SA_DGSSA_BR": ("Proposal", "#1760A6", "o"),
    "GA_SSA_reconstruction": ("GA–SSA adaptation", "#D96C37", "s"),
    "DECSO_inspired": ("DECSO adaptation", "#7D58A6", "^"),
    "EcoTaskOpt_inspired": ("EcoTaskOpt adaptation", "#23836C", "D"),
    "MinMin": ("Min–Min", "#5E6F7B", "x"),
}
plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 10, "axes.spines.top": False,
                     "axes.spines.right": False, "figure.dpi": 160, "savefig.dpi": 200})
fig, axes = plt.subplots(1, 2, figsize=(11.8, 4.3), sharey=False)
for ax, workload in zip(axes, ["uniform", "heavy"]):
    for method, (label, color, marker) in styles.items():
        z = summary[(summary.workload == workload) & (summary.method == method)].sort_values("tasks")
        ax.plot(z.tasks, z.makespan_median_s, color=color, marker=marker, lw=2, label=label)
    ax.set_title(f"{workload.capitalize()} task lengths")
    ax.set_xlabel("Number of tasks")
    ax.set_ylabel("Median CloudSim makespan (s)")
    ax.grid(axis="y", alpha=.25)
axes[1].legend(loc="upper left", fontsize=8, frameon=False)
fig.tight_layout()
fig.savefig(ROOT / "makespan_comparison.png", bbox_inches="tight")
plt.close(fig)

fig, axes = plt.subplots(1, 2, figsize=(11.8, 4.3), sharey=True)
colors = {"GA_SSA_reconstruction": "#D96C37", "DECSO_inspired": "#7D58A6",
          "EcoTaskOpt_inspired": "#23836C"}
for ax, workload in zip(axes, ["uniform", "heavy"]):
    for baseline in colors:
        z = paired[(paired.workload == workload) & (paired.baseline == baseline)].sort_values("tasks")
        ax.errorbar(z.tasks, z.mean_improvement_pct,
                    yerr=[z.mean_improvement_pct-z.bootstrap_95_low_pct,
                          z.bootstrap_95_high_pct-z.mean_improvement_pct],
                    label=styles[baseline][0], color=colors[baseline], marker="o", capsize=3, lw=1.8)
    ax.axhline(0, color="#333333", lw=1)
    ax.set_title(f"{workload.capitalize()} task lengths")
    ax.set_xlabel("Number of tasks")
    ax.grid(axis="y", alpha=.25)
axes[0].set_ylabel("Mean paired makespan improvement (%)")
axes[1].legend(loc="best", fontsize=8, frameon=False)
fig.tight_layout()
fig.savefig(ROOT / "paired_improvement.png", bbox_inches="tight")
plt.close(fig)

print(f"Analyzed {len(DATA)} runs across {DATA.seed.nunique()} unique seed values.")
print(paired[paired.baseline == "GA_SSA_reconstruction"].to_string(index=False,
      formatters={k: "{:.2f}".format for k in ["mean_improvement_pct", "bootstrap_95_low_pct", "bootstrap_95_high_pct"]}))
