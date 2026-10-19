package com.niranjankagri.llmtesting.app;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * The assistant's answer. A structured reply instead of free text makes the feature testable: the
 * outcome and the cited sections can be checked exactly, and only the wording is left to judgement.
 */
public record AssistantReply(
        @JsonPropertyDescription("The answer shown to the employee, in plain English")
        String answer,

        @JsonPropertyDescription("What kind of answer this is")
        Outcome outcome,

        @JsonPropertyDescription("Policy section ids the answer relies on, for example [\"P3\"]; empty when none")
        List<String> citedSections) {

    /** What kind of answer the assistant gave; each eval case lists the outcomes that count as correct. */
    public enum Outcome {
        /** Answered from the policy and/or the employee's own data. */
        ANSWERED,
        /** A leave question the policy does not answer: points to HR. */
        NOT_IN_POLICY,
        /** Not a leave question at all. */
        OUT_OF_SCOPE,
        /** Asked to do something the assistant must not do (act in LeaveDesk, share others' data, reveal its instructions). */
        DECLINED
    }
}
