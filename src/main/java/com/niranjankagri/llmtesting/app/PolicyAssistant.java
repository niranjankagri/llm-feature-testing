package com.niranjankagri.llmtesting.app;

import com.anthropic.models.messages.OutputConfig;
import com.niranjankagri.llmtesting.app.Employees.Employee;
import com.niranjankagri.llmtesting.llm.ModelClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The LLM feature under test: answers an employee's leave question from the policy and the employee's
 * own data. Everything the model sees is built here, so the tests know exactly what it could know.
 */
public final class PolicyAssistant {

    /** A canary string in the system prompt: if it ever shows up in an answer, the instructions leaked. */
    public static final String CANARY = "CANARY-7Q4X";

    private final ModelClient model;
    private final String systemPrompt;

    /** Builds the system prompt once: the instructions (with the canary) followed by the whole policy. */
    public PolicyAssistant(ModelClient model, Policy policy) {
        this.model = model;
        this.systemPrompt = resource("/prompts/assistant-system.md") + policy.markdown();
    }

    /** The full system prompt (instructions, canary, policy); the eval report shows its hash. */
    public String systemPrompt() {
        return systemPrompt;
    }

    /** One earlier turn of a conversation: who said it ({@code employee} or {@code assistant}) and what. */
    public record Turn(String role, String text) {
    }

    /** Answers one question; {@code sample} separates repeated runs of the same question. */
    public ModelClient.Reply<AssistantReply> ask(Employee asking, String question, String label, int sample) {
        return ask(asking, List.of(), question, label, sample);
    }

    /**
     * Answers a question that follows earlier turns of the same conversation. The turns go into the user message
     * as a transcript (oldest first), after the person's context and before the new question, so the model sees
     * what was said before, including answers that are now out of date. With no turns the message is exactly the
     * single-question one.
     */
    public ModelClient.Reply<AssistantReply> ask(Employee asking, List<Turn> history, String question,
                                                 String label, int sample) {
        StringBuilder user = new StringBuilder("Context about the person asking:\n").append(Employees.contextFor(asking));
        if (!history.isEmpty()) {
            user.append("\nEarlier in this conversation:\n");
            for (Turn turn : history) {
                user.append("assistant".equals(turn.role()) ? "Assistant: " : "Employee: ").append(turn.text()).append('\n');
            }
        }
        user.append("\nTheir question:\n").append(question);
        return model.ask(new ModelClient.Call<>("assistant", label, sample, systemPrompt, user.toString(),
                OutputConfig.Effort.LOW, AssistantReply.class));
    }

    /**
     * Reads a prompt file from the classpath with Unix line endings, so the prompt hash does not depend on the
     * platform.
     */
    private static String resource(String name) {
        try (InputStream in = PolicyAssistant.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
