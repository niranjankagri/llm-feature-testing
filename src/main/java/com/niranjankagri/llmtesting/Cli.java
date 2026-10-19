package com.niranjankagri.llmtesting;

import com.niranjankagri.llmtesting.app.AssistantReply;
import com.niranjankagri.llmtesting.app.Employees;
import com.niranjankagri.llmtesting.app.Policy;
import com.niranjankagri.llmtesting.app.PolicyAssistant;
import com.niranjankagri.llmtesting.eval.CaseSet;
import com.niranjankagri.llmtesting.eval.EvalReport;
import com.niranjankagri.llmtesting.eval.EvalRunner;
import com.niranjankagri.llmtesting.eval.Judge;
import com.niranjankagri.llmtesting.eval.JudgeCalibration;
import com.niranjankagri.llmtesting.llm.ClaudeClient;
import com.niranjankagri.llmtesting.llm.ModelClient;
import com.niranjankagri.llmtesting.llm.SampleClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Commands (through llm.cmd / llm.sh):
 * <pre>
 *   llm ask alice How many days do I have left?   one question to the assistant, live
 *   llm calibrate                                  judge vs. hand labels
 *   llm eval [--samples N] [--tag T] [--case ID]   golden set + calibration, report in target/eval/report.md
 * </pre>
 */
public final class Cli {

    /** Entry point only: no instances. */
    private Cli() {
    }

    /**
     * Picks the model client (hand-written samples with AI_MODE=sample, otherwise Claude live or replayed), loads
     * the policy and runs the command. Exit code: 0 passed, 1 failed, 2 wrong usage.
     */
    public static void main(String[] args) {
        List<String> rest = new ArrayList<>(Arrays.asList(args));
        String command = rest.isEmpty() ? "help" : rest.remove(0);
        // AI_MODE=sample: hand-written answers (ai/samples), no API key, never mistaken for Claude's.
        ModelClient model = "sample".equalsIgnoreCase(System.getenv().getOrDefault("AI_MODE", ""))
                ? SampleClient.fromRepository() : ClaudeClient.fromEnvironment();
        Policy policy = Policy.load();
        int exit = switch (command) {
            case "ask" -> ask(model, policy, rest);
            case "calibrate" -> calibrate(model, policy);
            case "eval" -> eval(model, policy, rest);
            default -> help();
        };
        System.exit(exit);
    }

    /**
     * One question to the assistant as one of the demo users; prints outcome, citations, the answer, latency and
     * tokens. The label comes from the question, so asking the same question again replays the same recording.
     */
    private static int ask(ModelClient model, Policy policy, List<String> args) {
        if (args.size() < 2) {
            System.out.println("Usage: llm ask <alice|bob|maria> <question>");
            return 2;
        }
        var asking = Employees.find(args.get(0)).orElseThrow(() -> new IllegalArgumentException("Unknown user " + args.get(0)));
        String question = String.join(" ", args.subList(1, args.size()));
        ModelClient.Reply<AssistantReply> reply = new PolicyAssistant(model, policy)
                .ask(asking, question, "adhoc-" + Math.abs(question.hashCode()), 1);
        System.out.println(reply.value().outcome() + " " + reply.value().citedSections() + "\n" + reply.value().answer()
                + "\n(" + reply.latencyMs() + " ms, " + reply.inputTokens() + " in / " + reply.outputTokens() + " out tokens"
                + (reply.replayed() ? ", replayed" : "") + ")");
        return 0;
    }

    /**
     * Runs the judge on the hand-labelled answers and prints how often it agrees; exit 1 when the judge is not
     * trusted.
     */
    private static int calibrate(ModelClient model, Policy policy) {
        JudgeCalibration.Result r = JudgeCalibration.run(new Judge(model, policy), JudgeCalibration.load(JudgeCalibration.DEFAULT));
        System.out.printf("Judge agreement %.0f%% (minimum %.0f%%), missed ungrounded answers: %d%n",
                r.agreement() * 100, r.minAgreement() * 100, r.missedUngrounded());
        r.outcomes().stream().filter(o -> !o.agrees()).forEach(o -> System.out.println("  disagrees on " + o.item().id()));
        return r.trusted() ? 0 : 1;
    }

    /**
     * The full eval: optional --samples, --tag and --case filters, judge calibration, every case, the report in
     * target/eval/report.md and one line per tag. Exit 1 when a tag is below its threshold or the judge is not
     * trusted.
     */
    private static int eval(ModelClient model, Policy policy, List<String> args) {
        CaseSet set = CaseSet.load(CaseSet.DEFAULT);
        List<String> tags = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < args.size() - 1; i++) {
            switch (args.get(i)) {
                case "--samples" -> set = set.withSamples(Integer.parseInt(args.get(++i)));
                case "--tag" -> tags.add(args.get(++i));
                case "--case" -> ids.add(args.get(++i));
                default -> { }
            }
        }
        set = set.only(tags, ids);
        String mode = model instanceof SampleClient ? ", sample (hand-written answers, NOT from Claude)"
                : model instanceof ClaudeClient c && c.isLive() ? ", live (answers are recorded)" : ", replay";
        System.out.println("Model " + model.model() + mode + ": " + set.cases().size() + " cases x " + set.samples()
                + " samples");
        JudgeCalibration.Result calibration = JudgeCalibration.run(new Judge(model, policy),
                JudgeCalibration.load(JudgeCalibration.DEFAULT));
        EvalRunner.Run run = new EvalRunner(model, policy).run(set);
        Path report = EvalReport.write(run, calibration);
        run.tags().forEach(t -> System.out.printf("  %-14s %3d / %-3d %s%n", t.tag(), t.passed(), t.total(),
                t.met() ? "ok" : "BELOW " + Math.round(t.threshold() * 100) + "%"));
        System.out.printf("Judge agreement %.0f%%%s%nReport: %s%n", calibration.agreement() * 100,
                calibration.trusted() ? "" : " (below minimum)", report);
        return run.passed() && calibration.trusted() ? 0 : 1;
    }

    /** Prints the commands and how the mode (live, replay, sample) is chosen. */
    private static int help() {
        System.out.println("""
                Commands (llm.cmd on Windows, ./llm.sh elsewhere):
                  ask <alice|bob|maria> <question>       one question to the assistant
                  calibrate                              the judge against hand-labelled answers
                  eval [--samples N] [--tag T] [--case ID]  golden set + calibration; report in target/eval/report.md
                Live with ANTHROPIC_API_KEY (answers are recorded); without it, recordings are replayed.
                AI_MODE=sample uses hand-written answers with planted flaws (ai/samples), not Claude.""");
        return 0;
    }
}
