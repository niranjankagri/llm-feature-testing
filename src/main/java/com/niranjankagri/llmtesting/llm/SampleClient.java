package com.niranjankagri.llmtesting.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niranjankagri.llmtesting.app.AssistantReply;
import com.niranjankagri.llmtesting.eval.CaseSet;
import com.niranjankagri.llmtesting.eval.Judge;
import com.niranjankagri.llmtesting.eval.JudgeCalibration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written answers instead of Claude (AI_MODE=sample), so the whole eval (checks, judge gating,
 * calibration, pass rates, report) can be seen without an API key.
 *
 * <p>The assistant answers each case with its hand-written reference answer from evals/cases.json, and
 * the judge answers with the hand label from evals/judge-calibration.json (grounded for eval cases).
 * {@code ai/samples/planted-flaws.json} replaces a few of those with planted bad answers, so the report
 * shows the harness catching them. Nothing here says anything about how well Claude does; the report
 * built from it opens with a banner that says so.
 */
public final class SampleClient implements ModelClient {

    /** Shown as the model in reports, so a sample run can never be mistaken for a real one. */
    public static final String MODEL = "hand-written samples (not Claude)";
    public static final Path PLANTED = Path.of("ai", "samples", "planted-flaws.json");

    private final Map<String, Object> answers = new HashMap<>();

    public SampleClient(CaseSet cases, JudgeCalibration.CalibrationSet calibration, Path planted) {
        ObjectMapper json = new ObjectMapper();
        for (CaseSet.EvalCase c : cases.cases()) {
            answers.put("assistant/" + c.id(), c.reference());
            answers.put("judge/" + c.id(), new Judge.Verdict(true, List.of()));
        }
        for (JudgeCalibration.Item item : calibration.items()) {
            answers.put("judge/calibration-" + item.id(), new Judge.Verdict(item.grounded(),
                    item.grounded() ? List.of() : List.of(item.note() == null ? "unsupported" : item.note())));
        }
        try {
            JsonNode root = json.readTree(planted.toFile());
            for (JsonNode a : root.get("assistant")) {
                answers.put("assistant/" + a.get("case").asText() + "/" + a.get("sample").asInt(),
                        json.treeToValue(a.get("answer"), AssistantReply.class));
            }
            for (JsonNode j : root.get("judge")) {
                answers.put("judge/" + j.get("label").asText() + "/" + j.get("sample").asInt(),
                        json.treeToValue(j.get("verdict"), Judge.Verdict.class));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + planted, e);
        }
    }

    /** The repository's own case set, calibration set and planted flaws. */
    public static SampleClient fromRepository() {
        return new SampleClient(CaseSet.load(CaseSet.DEFAULT), JudgeCalibration.load(JudgeCalibration.DEFAULT), PLANTED);
    }

    public static boolean isSampleModel(String model) {
        return MODEL.equals(model);
    }

    @Override
    public <T> Reply<T> ask(Call<T> call) {
        String base = call.purpose() + "/" + call.label();
        Object value = answers.getOrDefault(base + "/" + call.sample(), answers.get(base));
        if (value == null) {
            throw new ClaudeClient.ModelException("No sample answer for " + base + ": samples exist only for the"
                    + " golden set and the calibration items. Use live mode for other questions.");
        }
        // No call was made: no tokens, no latency, no cost.
        return new Reply<>(call.type().cast(value), true, 0, 0, 0, 0);
    }

    @Override
    public String model() {
        return MODEL;
    }
}
