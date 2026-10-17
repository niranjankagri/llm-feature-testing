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

    private Cli() {
    }

    public static void main(String[] args) {
        List<String> rest = new ArrayList<>(Arrays.asList(args));
        String command = rest.isEmpty() ? "help" : rest.remove(0);
        ClaudeClient model = ClaudeClient.fromEnvironment();
        Policy policy = Policy.load();
        int exit = switch (command) {
            case "ask" -> ask(model, policy, rest);
            case "calibrate" -> calibrate(model, policy);
            case "eval" -> eval(model, policy, rest);
            default -> help();
        };
        System.exit(exit);
    }

    private static int ask(ClaudeClient model, Policy policy, List<String> args) {
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

    private static int calibrate(ClaudeClient model, Policy policy) {
        JudgeCalibration.Result r = JudgeCalibration.run(new Judge(model, policy), JudgeCalibration.load(JudgeCalibration.DEFAULT));
        System.out.printf("Judge agreement %.0f%% (minimum %.0f%%), missed ungrounded answers: %d%n",
                r.agreement() * 100, r.minAgreement() * 100, r.missedUngrounded());
        r.outcomes().stream().filter(o -> !o.agrees()).forEach(o -> System.out.println("  disagrees on " + o.item().id()));
        return r.trusted() ? 0 : 1;
    }

    private static int eval(ClaudeClient model, Policy policy, List<String> args) {
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
        System.out.println("Model " + model.model() + (model.isLive() ? ", live (answers are recorded)" : ", replay")
                + ": " + set.cases().size() + " cases x " + set.samples() + " samples");
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

    private static int help() {
        System.out.println("""
                Commands (llm.cmd on Windows, ./llm.sh elsewhere):
                  ask <alice|bob|maria> <question>       one question to the assistant
                  calibrate                              the judge against hand-labelled answers
                  eval [--samples N] [--tag T] [--case ID]  golden set + calibration; report in target/eval/report.md
                Live with ANTHROPIC_API_KEY (answers are recorded); without it, recordings are replayed.""");
        return 0;
    }
}
