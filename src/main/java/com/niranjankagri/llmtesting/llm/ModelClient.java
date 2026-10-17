package com.niranjankagri.llmtesting.llm;

import com.anthropic.models.messages.OutputConfig;

/**
 * What the app and the judge need from a language model: one question in, one typed answer out.
 * {@link ClaudeClient} is the real implementation; unit tests use a stub, so the eval harness itself
 * can be tested without a model.
 */
public interface ModelClient {

    /**
     * One request.
     *
     * @param purpose folder under ai/recordings (assistant, judge)
     * @param label   readable part of the recording name (case id, sample number)
     * @param sample  1..n: repeated runs of the same question are separate samples, each recorded
     * @param system  system prompt
     * @param user    user message
     * @param effort  thinking depth
     * @param type    record class the answer must match (structured output)
     */
    record Call<T>(String purpose, String label, int sample, String system, String user,
                   OutputConfig.Effort effort, Class<T> type) {
    }

    /** The typed answer with what it cost. Replayed answers carry the numbers of the recorded call. */
    record Reply<T>(T value, boolean replayed, long inputTokens, long outputTokens, long latencyMs, double costUsd) {
    }

    <T> Reply<T> ask(Call<T> call);

    /** Model id, part of every recording key. */
    String model();
}
