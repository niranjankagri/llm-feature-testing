package com.niranjankagri.llmtesting.eval;

import com.anthropic.models.messages.OutputConfig;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.niranjankagri.llmtesting.app.Employees;
import com.niranjankagri.llmtesting.app.Employees.Employee;
import com.niranjankagri.llmtesting.app.Policy;
import com.niranjankagri.llmtesting.llm.ModelClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * LLM-as-judge for the one property text checks cannot decide: is every claim in the answer supported
 * by the policy or the person's data? Its own accuracy is measured against hand-labelled answers
 * ({@link JudgeCalibration}) before its verdicts are trusted in the eval.
 */
public final class Judge {

    /** The judge's answer schema. */
    public record Verdict(
            @JsonPropertyDescription("True only when every factual claim in the answer is supported")
            boolean grounded,

            @JsonPropertyDescription("Each unsupported claim, quoted from the answer; empty when grounded")
            List<String> unsupportedClaims) {
    }

    private final ModelClient model;
    private final String systemPrompt;
    private final Policy policy;

    public Judge(ModelClient model, Policy policy) {
        this.model = model;
        this.policy = policy;
        this.systemPrompt = resource("/prompts/judge-system.md");
    }

    public ModelClient.Reply<Verdict> judge(String user, String question, String answer, String label, int sample) {
        Employee asking = Employees.find(user).orElseThrow(() -> new IllegalArgumentException("Unknown user " + user));
        String message = "Leave policy:\n" + policy.markdown()
                + "\n\nData the assistant had about the person asking:\n" + Employees.contextFor(asking)
                + "\nQuestion:\n" + question
                + "\n\nAssistant's answer:\n" + answer;
        return model.ask(new ModelClient.Call<>("judge", label, sample, systemPrompt, message,
                OutputConfig.Effort.MEDIUM, Verdict.class));
    }

    private static String resource(String name) {
        try (InputStream in = Judge.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
