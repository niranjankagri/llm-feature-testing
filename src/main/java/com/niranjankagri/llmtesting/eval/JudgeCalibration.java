package com.niranjankagri.llmtesting.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niranjankagri.llmtesting.llm.ModelClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the tester: runs the judge on hand-labelled answers (evals/judge-calibration.json) and
 * measures how often it agrees with the human label. If agreement is below the file's minimum, the
 * judge's verdicts in the eval are not trustworthy and the eval says so instead of reporting them.
 */
public final class JudgeCalibration {

    public static final Path DEFAULT = Path.of("evals", "judge-calibration.json");

    /**
     * One hand-labelled answer: who asked what, the answer, whether it is grounded, and for ungrounded ones what
     * is wrong.
     */
    public record Item(String id, String user, String question, String answer, boolean grounded, String note) {
    }

    /** evals/judge-calibration.json: the labelled items and the agreement the judge must reach to be trusted. */
    public record CalibrationSet(String about, double minAgreement, List<Item> items) {
    }

    /** One item: the human label next to the judge's verdict. */
    public record Outcome(Item item, Judge.Verdict verdict) {
        public boolean agrees() {
            return item.grounded() == verdict.grounded();
        }
    }

    /** All outcomes plus the agreement rate and the bar it had to clear. */
    public record Result(List<Outcome> outcomes, double minAgreement, double costUsd) {

        public double agreement() {
            return outcomes.isEmpty() ? 0 : (double) outcomes.stream().filter(Outcome::agrees).count() / outcomes.size();
        }

        public boolean trusted() {
            return agreement() >= minAgreement;
        }

        /** False negatives matter most: an ungrounded answer the judge waved through. */
        public long missedUngrounded() {
            return outcomes.stream().filter(o -> !o.item().grounded() && o.verdict().grounded()).count();
        }
    }

    /** Static helpers only: no instances. */
    private JudgeCalibration() {
    }

    /** Reads the calibration file. */
    public static CalibrationSet load(Path file) {
        try {
            return new ObjectMapper().readValue(file.toFile(), CalibrationSet.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /**
     * Asks the judge about every labelled answer and collects its verdicts next to the human labels, with the
     * total cost.
     */
    public static Result run(Judge judge, CalibrationSet set) {
        List<Outcome> outcomes = new ArrayList<>();
        double cost = 0;
        for (Item item : set.items()) {
            ModelClient.Reply<Judge.Verdict> reply = judge.judge(item.user(), item.question(), item.answer(),
                    "calibration-" + item.id(), 1);
            outcomes.add(new Outcome(item, reply.value()));
            cost += Math.max(0, reply.costUsd());
        }
        return new Result(outcomes, set.minAgreement(), cost);
    }
}
