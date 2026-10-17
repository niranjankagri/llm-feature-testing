package com.niranjankagri.llmtesting.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonSchemaLocalValidation;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredOutputConfig;
import com.anthropic.models.messages.StructuredTextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Claude through the official Anthropic Java SDK, with recording and replay.
 *
 * <p><b>live</b> (ANTHROPIC_API_KEY set): calls the Messages API and saves each answer, its token
 * counts and latency under ai/recordings/. <b>replay</b> (no key, or AI_MODE=replay): answers only from
 * recordings, so CI and every clone get the same answers for free. A recording is keyed on the model,
 * the system prompt, the user message and the sample number: changing the prompt or the policy makes
 * replay fail with a clear message instead of silently using a stale answer.
 */
public final class ClaudeClient implements ModelClient {

    public static final String DEFAULT_MODEL = "claude-opus-5-5";
    public static final Path RECORDINGS = Path.of("ai", "recordings");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Claude declined, was cut off, or replay had no recording. */
    public static final class ModelException extends RuntimeException {
        public ModelException(String message) {
            super(message);
        }
    }

    private final String model;
    private final boolean live;
    private AnthropicClient client;

    private ClaudeClient(String model, boolean live) {
        this.model = model;
        this.live = live;
    }

    /** Mode and model from the environment: AI_MODE (live|replay), AI_MODEL, ANTHROPIC_API_KEY. */
    public static ClaudeClient fromEnvironment() {
        String mode = System.getenv().getOrDefault("AI_MODE", "");
        boolean hasKey = !System.getenv().getOrDefault("ANTHROPIC_API_KEY", "").isBlank();
        boolean live = mode.isBlank() ? hasKey : mode.equalsIgnoreCase("live");
        return new ClaudeClient(System.getenv().getOrDefault("AI_MODEL", DEFAULT_MODEL), live);
    }

    /** Replay-only client, for tests. */
    public static ClaudeClient replayOnly(String model) {
        return new ClaudeClient(model, false);
    }

    @Override
    public String model() {
        return model;
    }

    public boolean isLive() {
        return live;
    }

    @Override
    public <T> Reply<T> ask(Call<T> call) {
        Path recording = recordingFile(call);
        if (!live) {
            return replay(call, recording);
        }
        long start = System.nanoTime();
        StructuredMessage<T> message = client().messages().create(MessageCreateParams.builder()
                .model(model)
                .maxTokens(16_000L)
                // The system prompt (instructions + policy) is the same for every question: cache it.
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(call.system())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .outputConfig(StructuredOutputConfig.<T>builder()
                        .effort(call.effort())
                        .format(call.type(), JsonSchemaLocalValidation.YES)
                        .build())
                // Server-side refusal fallback: a declined request is retried on a fallback model.
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .addUserMessage(call.user())
                .build());
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        T value = checkedAnswer(message, call);
        Usage usage = message.usage();
        long input = usage.inputTokens() + usage.cacheCreationInputTokens().orElse(0L)
                + usage.cacheReadInputTokens().orElse(0L);
        Reply<T> reply = new Reply<>(value, false, input, usage.outputTokens(), latencyMs, costUsd(usage));
        save(recording, call, reply);
        return reply;
    }

    /** ai/recordings/&lt;purpose&gt;/&lt;label&gt;-s&lt;sample&gt;-&lt;12 hex of SHA-256&gt;.json */
    public Path recordingFile(Call<?> call) {
        String hash = Hashes.sha256(model + "\n" + call.sample() + "\n" + call.system() + "\n" + call.user()).substring(0, 12);
        return RECORDINGS.resolve(call.purpose()).resolve(call.label() + "-s" + call.sample() + "-" + hash + ".json");
    }

    private static <T> T checkedAnswer(StructuredMessage<T> message, Call<T> call) {
        StopReason stop = message.stopReason().orElse(StopReason.END_TURN);
        if (stop.equals(StopReason.REFUSAL)) {
            throw new ModelException("Claude declined " + call.purpose() + " " + call.label() + ": "
                    + message.stopDetails().map(d -> d.explanation().orElse("no explanation")).orElse("no details"));
        }
        if (stop.equals(StopReason.MAX_TOKENS)) {
            throw new ModelException("Answer for " + call.label() + " hit the token limit");
        }
        return message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(StructuredTextBlock::text)
                .findFirst()
                .orElseThrow(() -> new ModelException("No answer for " + call.label()));
    }

    private <T> Reply<T> replay(Call<T> call, Path recording) {
        if (!Files.exists(recording)) {
            throw new ModelException("No recording for " + call.purpose() + " " + call.label() + " sample "
                    + call.sample() + " (" + recording + "). The prompt, the policy or the question changed since"
                    + " recording: set ANTHROPIC_API_KEY and run again to record.");
        }
        try {
            JsonNode saved = JSON.readTree(recording.toFile());
            return new Reply<>(JSON.treeToValue(saved.get("answer"), call.type()), true,
                    saved.get("inputTokens").asLong(), saved.get("outputTokens").asLong(),
                    saved.get("latencyMs").asLong(), saved.get("costUsd").asDouble());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> void save(Path recording, Call<T> call, Reply<T> reply) {
        ObjectNode root = JSON.createObjectNode();
        root.put("purpose", call.purpose());
        root.put("label", call.label());
        root.put("sample", call.sample());
        root.put("model", model);
        root.put("recordedOn", LocalDate.now().toString());
        root.put("inputTokens", reply.inputTokens());
        root.put("outputTokens", reply.outputTokens());
        root.put("latencyMs", reply.latencyMs());
        root.put("costUsd", reply.costUsd());
        root.set("answer", JSON.valueToTree(reply.value()));
        try {
            Files.createDirectories(recording.getParent());
            JSON.writeValue(recording.toFile(), root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Claude Opus 5.5 list prices per million tokens; -1 for other models. */
    private double costUsd(Usage usage) {
        if (!model.equals(DEFAULT_MODEL)) {
            return -1;
        }
        return (usage.inputTokens() * 4.0
                + usage.cacheCreationInputTokens().orElse(0L) * 5.0
                + usage.cacheReadInputTokens().orElse(0L) * 0.20
                + usage.outputTokens() * 20.0) / 1_000_000;
    }

    private synchronized AnthropicClient client() {
        if (client == null) {
            client = AnthropicOkHttpClient.fromEnv();
        }
        return client;
    }
}
