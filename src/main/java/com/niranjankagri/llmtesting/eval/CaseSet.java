package com.niranjankagri.llmtesting.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niranjankagri.llmtesting.app.AssistantReply;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The golden set in evals/cases.json: how often each question is asked ({@code samples}), the pass-rate
 * threshold per tag, and the cases.
 */
public record CaseSet(String about, int samples, Map<String, Double> thresholds, List<EvalCase> cases) {

    public static final Path DEFAULT = Path.of("evals", "cases.json");
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    /** What a correct answer must look like. Missing lists mean "no such check". */
    public record Expectation(List<AssistantReply.Outcome> outcomes, List<String> mustMatch, List<String> mustNotMatch,
                              List<String> mustCite, boolean judge) {

        public Expectation {
            mustMatch = mustMatch == null ? List.of() : mustMatch;
            mustNotMatch = mustNotMatch == null ? List.of() : mustNotMatch;
            mustCite = mustCite == null ? List.of() : mustCite;
        }
    }

    /**
     * One question.
     *
     * @param reference a hand-written good answer; it must pass every deterministic check, which proves
     *                  the case is passable and the checks are not too strict
     */
    public record EvalCase(String id, String tag, String user, String question, Expectation expect,
                           AssistantReply reference) {
    }

    /**
     * Reads evals/cases.json. Unknown fields fail the load, so a typo in an expectation cannot silently switch a
     * check off.
     */
    public static CaseSet load(Path file) {
        try {
            return JSON.readValue(file.toFile(), CaseSet.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /** Only the cases of the given tags (all when the list is empty). */
    public CaseSet only(List<String> tags, List<String> ids) {
        List<EvalCase> picked = cases.stream()
                .filter(c -> tags.isEmpty() || tags.contains(c.tag()))
                .filter(c -> ids.isEmpty() || ids.contains(c.id()))
                .toList();
        return new CaseSet(about, samples, thresholds, picked);
    }

    /** The same cases asked n times each (the --samples option). */
    public CaseSet withSamples(int n) {
        return new CaseSet(about, n, thresholds, cases);
    }
}
