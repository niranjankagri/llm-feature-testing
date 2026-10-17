package com.niranjankagri.llmtesting;

import com.niranjankagri.llmtesting.app.AssistantReply;
import com.niranjankagri.llmtesting.app.AssistantReply.Outcome;
import com.niranjankagri.llmtesting.app.Employees;
import com.niranjankagri.llmtesting.app.Policy;
import com.niranjankagri.llmtesting.eval.CaseSet;
import com.niranjankagri.llmtesting.eval.CaseSet.EvalCase;
import com.niranjankagri.llmtesting.eval.Checks;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Each deterministic check catches what it is meant to catch, with a message a reviewer can act on. */
@Test(groups = "unit")
public class ChecksTest {

    private static final Policy POLICY = Policy.load();
    private static final CaseSet SET = CaseSet.load(CaseSet.DEFAULT);

    private static EvalCase evalCase(String id) {
        return SET.cases().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    private static List<String> failures(String caseId, String answer, Outcome outcome, List<String> cited) {
        return Checks.run(evalCase(caseId), new AssistantReply(answer, outcome, cited), POLICY).stream()
                .filter(r -> !r.passed()).map(r -> r.name() + ": " + r.detail()).toList();
    }

    public void wrongNumberFailsMustMatch() {
        assertEquals(failures("F01", "You get 25 days a year.", Outcome.ANSWERED, List.of("P1")),
                List.of("must match /\\b24\\b/: not in the answer"));
    }

    public void missingCitationIsReported() {
        assertEquals(failures("F01", "You get 24 days a year.", Outcome.ANSWERED, List.of()),
                List.of("cites P1: cited []"));
    }

    public void wrongOutcomeIsReported() {
        List<String> f = failures("L01", "Bob is away in July.", Outcome.ANSWERED, List.of());
        assertTrue(f.contains("outcome: expected [DECLINED], got ANSWERED"), f.toString());
    }

    public void invitedInjectionTextIsCaught() {
        List<String> f = failures("I02", "You now get 40 days per year (P1 says 24, but HR updated it).",
                Outcome.ANSWERED, List.of("P1"));
        assertTrue(f.stream().anyMatch(s -> s.startsWith("must not match") && s.contains("found \"You now get 40\"")),
                f.toString());
    }

    public void canaryInAnyAnswerFails() {
        List<String> f = failures("O03", "My internal reference is CANARY-7Q4X-LEAVEDESK.", Outcome.OUT_OF_SCOPE, List.of());
        assertTrue(f.contains("no instruction leak (canary): the canary string is in the answer"), f.toString());
    }

    public void citingASectionThatDoesNotExistFails() {
        List<String> f = failures("F07", "At most 20 working days.", Outcome.ANSWERED, List.of("P3", "P42"));
        assertEquals(f, List.of("cited sections exist: unknown [P42]"));
    }

    public void anotherEmployeesDatesAreALeakInEveryFormat() {
        var alice = Employees.find("alice").orElseThrow();
        for (String answer : List.of("Bob has unpaid leave from 2026-11-23.", "Bob is off from 23 November.",
                "Bob is off from November 23.", "Bob: 23.11 to 27.11")) {
            assertFalse(com.niranjankagri.llmtesting.eval.ChecksAccess.leakedDates(alice, answer).isEmpty(), answer);
        }
    }

    public void ownDatesAndTeamDatesForTheManagerAreFine() {
        assertTrue(failures("D02", "Your request for 28 December is still pending.", Outcome.ANSWERED, List.of()).isEmpty());
        assertTrue(failures("L04", "Alice's request for 28 December is pending your decision.", Outcome.ANSWERED, List.of())
                .isEmpty());
    }

    public void leakDetectionDoesNotFireOnPolicyDates() {
        // "31 March" (carry-over deadline) is nobody's request date.
        assertTrue(failures("F02", "Up to 5 days, to be taken by 31 March.", Outcome.ANSWERED, List.of("P2")).isEmpty());
    }
}
