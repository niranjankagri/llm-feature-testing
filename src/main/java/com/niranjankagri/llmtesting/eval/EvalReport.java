package com.niranjankagri.llmtesting.eval;

import com.niranjankagri.llmtesting.eval.EvalRunner.Run;
import com.niranjankagri.llmtesting.eval.EvalRunner.Sample;
import com.niranjankagri.llmtesting.eval.EvalRunner.TagResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Writes target/eval/report.md: per-tag pass rates, judge calibration, failures with reasons, cost and latency. */
public final class EvalReport {

    public static final Path OUTPUT = Path.of("target", "eval");

    private EvalReport() {
    }

    public static Path write(Run run, JudgeCalibration.Result calibration) {
        StringBuilder md = new StringBuilder("# LeaveDesk assistant: eval report\n\n");
        long replayed = run.samples().stream().filter(Sample::replayed).count();
        md.append("Model `").append(run.model()).append("`, system prompt `").append(run.promptHash()).append("`, ")
                .append(run.samples().size()).append(" answers")
                .append(replayed == run.samples().size() ? " (replayed from recordings)" : replayed == 0 ? " (live)"
                        : " (" + replayed + " replayed)")
                .append(". Result: **").append(run.passed() && (calibration == null || calibration.trusted())
                        ? "PASSED" : "FAILED").append("**\n\n");

        md.append("## Pass rate per tag\n\n| Tag | Passed | Rate | Threshold | |\n|---|---|---|---|---|\n");
        for (TagResult t : run.tags()) {
            md.append(String.format(Locale.ROOT, "| %s | %d / %d | %.0f%% | %.0f%% | %s |%n", t.tag(), t.passed(),
                    t.total(), t.rate() * 100, t.threshold() * 100, t.met() ? "ok" : "**below**"));
        }

        md.append("\n## Judge calibration\n\n");
        if (calibration == null) {
            md.append("Not run in this eval.\n");
        } else {
            md.append(String.format(Locale.ROOT,
                    "Agreement with hand labels: **%.0f%%** (%d items, minimum %.0f%%); ungrounded answers the judge"
                            + " missed: %d. %s%n", calibration.agreement() * 100, calibration.outcomes().size(),
                    calibration.minAgreement() * 100, calibration.missedUngrounded(),
                    calibration.trusted() ? "The judge's verdicts below are used."
                            : "**Below the minimum: do not trust the groundedness verdicts.**"));
            calibration.outcomes().stream().filter(o -> !o.agrees()).forEach(o -> md.append("- ").append(o.item().id())
                    .append(": label ").append(o.item().grounded() ? "grounded" : "ungrounded").append(", judge said ")
                    .append(o.verdict().grounded() ? "grounded" : "ungrounded " + o.verdict().unsupportedClaims())
                    .append("\n"));
        }

        md.append("\n## Per case\n\n| Case | Tag | Passed | Note |\n|---|---|---|---|\n");
        Map<String, List<Sample>> byCase = run.samples().stream()
                .collect(Collectors.groupingBy(s -> s.evalCase().id(), java.util.LinkedHashMap::new, Collectors.toList()));
        byCase.forEach((id, samples) -> {
            long ok = samples.stream().filter(Sample::passed).count();
            String note = ok == samples.size() ? "" : ok == 0 ? "fails every time" : "flaky: passes " + ok + " of " + samples.size();
            md.append("| ").append(id).append(" | ").append(samples.get(0).evalCase().tag()).append(" | ")
                    .append(ok).append(" / ").append(samples.size()).append(" | ").append(note).append(" |\n");
        });

        List<Sample> failed = run.samples().stream().filter(s -> !s.passed()).toList();
        md.append("\n## Failures\n\n");
        if (failed.isEmpty()) {
            md.append("None.\n");
        }
        for (Sample s : failed) {
            md.append("### ").append(s.evalCase().id()).append(" sample ").append(s.sample()).append(" (")
                    .append(s.evalCase().tag()).append(")\n\n")
                    .append("**Question** (").append(s.evalCase().user()).append("): ").append(s.evalCase().question())
                    .append("\n\n**Answer** (").append(s.reply().outcome()).append(", cites ")
                    .append(s.reply().citedSections()).append("): ").append(s.reply().answer()).append("\n\n");
            s.failures().forEach(f -> md.append("- ").append(f).append("\n"));
            md.append("\n");
        }

        List<Long> latencies = run.samples().stream().map(Sample::latencyMs).sorted().toList();
        double cost = run.samples().stream().mapToDouble(Sample::costUsd).sum()
                + (calibration == null ? 0 : calibration.costUsd());
        md.append(String.format(Locale.ROOT, "## Cost and latency%n%nAssistant latency p50 %d ms, p95 %d ms; total cost"
                        + " of the run when recorded about $%.2f.%n", percentile(latencies, 50), percentile(latencies, 95), cost));
        try {
            Files.createDirectories(OUTPUT);
            Path file = OUTPUT.resolve("report.md");
            Files.writeString(file, md.toString());
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
