CC_2026_PG_XX - executable code
================================
SchedulerExperiment.java : CloudSim Plus 8.5.7 batch scheduler and experiment driver.
analyze.py               : computes summary and paired statistics and draws the figures.
data/runs_1.csv          : 3,000 run-level results of the final (corrected) experiment.
data/summary_1.csv, data/paired_improvements_1.csv : derived tables.

Requirements: Java 17+ (tested on Java 25), CloudSim Plus 8.5.7 jar and its
dependencies (commons-math3, commons-lang3, commons-collections4, gson, logback, slf4j).

Self-test:
  java -cp "target/classes;lib/*" edu.project.SchedulerExperiment selftest

Full run (3,000 runs, 30 seeds):
  java -cp "target/classes;lib/*" edu.project.SchedulerExperiment results 30

Analysis:
  python analyze.py results
