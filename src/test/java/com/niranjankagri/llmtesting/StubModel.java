package com.niranjankagri.llmtesting;

import com.niranjankagri.llmtesting.llm.ModelClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Test double for {@link ModelClient}: answers are scripted per "purpose/label/sample", so the eval
 * harness (checks, judge gating, pass rates, report) can be tested without calling a model. It is never
 * used to produce eval results.
 */
public final class StubModel implements ModelClient {

    private final Map<String, Object> scripted = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private Function<Call<?>, Object> fallback = call -> {
        throw new IllegalStateException("No scripted answer for " + key(call));
    };

    public StubModel answer(String purpose, String label, int sample, Object value) {
        scripted.put(purpose + "/" + label + "/" + sample, value);
        return this;
    }

    /** Answer for every call not scripted explicitly. */
    public StubModel otherwise(Function<Call<?>, Object> answer) {
        this.fallback = answer;
        return this;
    }

    /** "purpose/label/sample" of every call made, in order. */
    public List<String> calls() {
        return calls;
    }

    @Override
    public <T> Reply<T> ask(Call<T> call) {
        calls.add(key(call));
        Object value = scripted.containsKey(key(call)) ? scripted.get(key(call)) : fallback.apply(call);
        return new Reply<>(call.type().cast(value), false, 1000, 100, 50, 0.01);
    }

    @Override
    public String model() {
        return "stub";
    }

    private static String key(Call<?> call) {
        return call.purpose() + "/" + call.label() + "/" + call.sample();
    }
}
