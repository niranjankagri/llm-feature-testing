package com.niranjankagri.llmtesting.eval;

import com.niranjankagri.llmtesting.app.AssistantReply;
import com.niranjankagri.llmtesting.app.Employees;
import com.niranjankagri.llmtesting.app.Policy;
import com.niranjankagri.llmtesting.app.PolicyAssistant;
import com.niranjankagri.llmtesting.eval.CaseSet.EvalCase;
import com.niranjankagri.llmtesting.llm.Hashes;
import com.niranjankagri.llmtesting.llm.ModelClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the golden set: every case {@code samples} times (LLM answers vary, so one run proves little),
 * deterministic checks on every answer, then the judge on answers that passed them and are marked for
 * judging. A sample passes when all its checks pass; a tag passes when its pass rate reaches its threshold.
 */
public final class EvalRunner {

    /** One answer to one case and what the checks and the judge said about it. */
    public record Sample(EvalCase evalCase, int sample, AssistantReply reply, List<Checks.Result> checks,
                         Judge.Verdict verdict, long latencyMs, double costUsd, boolean replayed) {

        public boolean passed() {
            return checks.stream().allMatch(Checks.Result::passed) && (verdict == null || verdict.grounded());
        }

        /**
         * Why this sample failed, one line per failed check plus the judge's unsupported claims; empty when it
         * passed.
         */
        public List<String> failures() {
            List<String> out = new ArrayList<>();
            checks.stream().filter(c -> !c.passed()).forEach(c -> out.add(c.name() + ": " + c.detail()));
            if (verdict != null && !verdict.grounded()) {
                out.add("judge: unsupported " + verdict.unsupportedClaims());
            }
            return out;
        }
    }

    /** Pass rate of one tag against its threshold. */
    public record TagResult(String tag, int passed, int total, double threshold) {

        public double rate() {
            return total == 0 ? 0 : (double) passed / total;
        }

        public boolean met() {
            return rate() >= threshold;
        }
    }

    /** The whole run. */
    public record Run(List<Sample> samples, List<TagResult> tags, String model, String promptHash) {

        public boolean passed() {
            return tags.stream().allMatch(TagResult::met);
        }
    }

    private final PolicyAssistant assistant;
    private final Judge judge;
    private final Policy policy;
    private final ModelClient model;

    /**
     * The assistant and the judge share one model client, so a run is either all live, all replayed or all
     * samples.
     */
    public EvalRunner(ModelClient model, Policy policy) {
        this.model = model;
        this.policy = policy;
        this.assistant = new PolicyAssistant(model, policy);
        this.judge = new Judge(model, policy);
    }

    /**
     * Asks every case set.samples() times, runs the checks on each answer, asks the judge only when the checks
     * passed and the case wants it, and sums up pass rates per tag.
     */
    public Run run(CaseSet set) {
        List<Sample> samples = new ArrayList<>();
        for (EvalCase c : set.cases()) {
            Employees.Employee asking = Employees.find(c.user())
                    .orElseThrow(() -> new IllegalArgumentException(c.id() + ": unknown user " + c.user()));
            for (int s = 1; s <= set.samples(); s++) {
                ModelClient.Reply<AssistantReply> reply = assistant.ask(asking, c.question(), c.id(), s);
                List<Checks.Result> checks = Checks.run(c, reply.value(), policy);
                Judge.Verdict verdict = null;
                double cost = Math.max(0, reply.costUsd());
                // The judge costs a call: only ask when the cheap checks passed and the case wants it.
                if (c.expect().judge() && checks.stream().allMatch(Checks.Result::passed)) {
                    ModelClient.Reply<Judge.Verdict> judged = judge.judge(c.user(), c.question(),
                            reply.value().answer(), c.id(), s);
                    verdict = judged.value();
                    cost += Math.max(0, judged.costUsd());
                }
                samples.add(new Sample(c, s, reply.value(), checks, verdict, reply.latencyMs(), cost, reply.replayed()));
            }
        }
        return new Run(samples, tagResults(samples, set), model.model(),
                Hashes.sha256(assistant.systemPrompt()).substring(0, 12));
    }

    /**
     * Passed and total samples per tag, in the order the tags first appear, with each tag's threshold (100% if the
     * file names none).
     */
    static List<TagResult> tagResults(List<Sample> samples, CaseSet set) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (Sample s : samples) {
            int[] c = counts.computeIfAbsent(s.evalCase().tag(), t -> new int[2]);
            c[1]++;
            if (s.passed()) {
                c[0]++;
            }
        }
        List<TagResult> out = new ArrayList<>();
        counts.forEach((tag, c) -> out.add(new TagResult(tag, c[0], c[1], set.thresholds().getOrDefault(tag, 1.0))));
        return out;
    }
}
