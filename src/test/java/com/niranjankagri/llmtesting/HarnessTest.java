package com.niranjankagri.llmtesting;

import com.niranjankagri.llmtesting.app.AssistantReply;
import com.niranjankagri.llmtesting.app.AssistantReply.Outcome;
import com.niranjankagri.llmtesting.app.Employees;
import com.niranjankagri.llmtesting.app.Policy;
import com.niranjankagri.llmtesting.eval.CaseSet;
import com.niranjankagri.llmtesting.eval.EvalReport;
import com.niranjankagri.llmtesting.eval.EvalRunner;
import com.niranjankagri.llmtesting.eval.Judge;
import com.niranjankagri.llmtesting.eval.JudgeCalibration;
import com.niranjankagri.llmtesting.llm.ClaudeClient;
import com.niranjankagri.llmtesting.llm.ModelClient;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/** The eval runner, report, calibration and recording logic, driven by a scripted stub model. */
@Test(groups = "unit")
public class HarnessTest {

    private static final Policy POLICY = Policy.load();
    private static final CaseSet SET = CaseSet.load(CaseSet.DEFAULT);
    private static final Judge.Verdict GROUNDED = new Judge.Verdict(true, List.of());

    public void employeeNumbersMatchTheirRequests() {
        assertEquals(Employees.find("alice").orElseThrow().annualRemaining(), 16);
        assertEquals(Employees.find("bob").orElseThrow().annualRemaining(), 14);
        assertEquals(Employees.find("maria").orElseThrow().annualRemaining(), 19);
    }

    public void contextHoldsOnlyWhatTheAskerMaySee() {
        String alice = Employees.contextFor(Employees.find("alice").orElseThrow());
        assertFalse(alice.contains("Bob") || alice.contains("2026-11-23"), alice);
        String maria = Employees.contextFor(Employees.find("maria").orElseThrow());
        assertTrue(maria.contains("Bob Schmidt") && maria.contains("#18 UNPAID 2026-11-23"), maria);
    }

    public void judgeRunsOnlyWhenTheCheapChecksPassed() {
        CaseSet one = SET.only(List.of(), List.of("F01")).withSamples(2);
        StubModel stub = new StubModel()
                .answer("assistant", "F01", 1, new AssistantReply("You get 24 days.", Outcome.ANSWERED, List.of("P1")))
                .answer("assistant", "F01", 2, new AssistantReply("You get 25 days.", Outcome.ANSWERED, List.of("P1")))
                .answer("judge", "F01", 1, GROUNDED);
        EvalRunner.Run run = new EvalRunner(stub, POLICY).run(one);
        assertEquals(stub.calls(), List.of("assistant/F01/1", "judge/F01/1", "assistant/F01/2"),
                "sample 2 failed a text check, so the judge was not paid for");
        assertEquals(run.tags().get(0).passed(), 1);
        assertEquals(run.tags().get(0).total(), 2);
        assertFalse(run.passed(), "50% is below the 90% policy_fact threshold");
    }

    public void ungroundedVerdictFailsASampleThatPassedTheChecks() {
        CaseSet one = SET.only(List.of(), List.of("F01")).withSamples(1);
        StubModel stub = new StubModel()
                .answer("assistant", "F01", 1, new AssistantReply("You get 24 days, plus 2 for each year of service.",
                        Outcome.ANSWERED, List.of("P1")))
                .answer("judge", "F01", 1, new Judge.Verdict(false, List.of("plus 2 for each year of service")));
        EvalRunner.Sample s = new EvalRunner(stub, POLICY).run(one).samples().get(0);
        assertFalse(s.passed());
        assertEquals(s.failures(), List.of("judge: unsupported [plus 2 for each year of service]"));
    }

    public void referenceAnswersPassTheWholeSetAndTheReportSaysSo() throws Exception {
        // Scripting the hand-written reference answers through the harness: every tag must reach 100%.
        StubModel stub = new StubModel().otherwise(call -> call.purpose().equals("judge") ? GROUNDED
                : SET.cases().stream().filter(c -> c.id().equals(call.label())).findFirst().orElseThrow().reference());
        EvalRunner.Run run = new EvalRunner(stub, POLICY).run(SET.withSamples(1));
        run.tags().forEach(t -> assertEquals(t.rate(), 1.0, t.tag()));
        String report = Files.readString(EvalReport.write(run, null));
        assertTrue(report.contains("Result: **PASSED**") && report.contains("| injection | 6 / 6 | 100% | 100% | ok |"), report);
    }

    public void flakyCaseIsCalledFlakyInTheReport() throws Exception {
        CaseSet one = SET.only(List.of(), List.of("O03")).withSamples(3);
        StubModel stub = new StubModel()
                .answer("assistant", "O03", 1, new AssistantReply("I only help with leave.", Outcome.OUT_OF_SCOPE, List.of()))
                .answer("assistant", "O03", 2, new AssistantReply("Canberra.", Outcome.OUT_OF_SCOPE, List.of()))
                .answer("assistant", "O03", 3, new AssistantReply("I only help with leave.", Outcome.OUT_OF_SCOPE, List.of()));
        String report = Files.readString(EvalReport.write(new EvalRunner(stub, POLICY).run(one), null));
        assertTrue(report.contains("| O03 | out_of_scope | 2 / 3 | flaky: passes 2 of 3 |"), report);
        assertTrue(report.contains("found \"Canberra\""), report);
    }

    public void calibrationCountsAgreementAndMissedUngroundedAnswers() {
        JudgeCalibration.CalibrationSet cal = JudgeCalibration.load(JudgeCalibration.DEFAULT);
        // A lenient judge that calls everything grounded agrees on exactly the grounded half.
        StubModel lenient = new StubModel().otherwise(call -> GROUNDED);
        JudgeCalibration.Result r = JudgeCalibration.run(new Judge(lenient, POLICY), cal);
        assertEquals(r.agreement(), 0.5);
        assertEquals(r.missedUngrounded(), cal.items().size() / 2);
        assertFalse(r.trusted(), "a judge that approves everything must not be trusted");
    }

    public void recordingKeyChangesWithSampleAndPrompt() {
        ClaudeClient replay = ClaudeClient.replayOnly("claude-opus-5-5");
        var base = new ModelClient.Call<>("assistant", "F01", 1, "system v1", "question", null, AssistantReply.class);
        assertEquals(replay.recordingFile(base), replay.recordingFile(base));
        assertNotEquals(replay.recordingFile(base), replay.recordingFile(
                new ModelClient.Call<>("assistant", "F01", 2, "system v1", "question", null, AssistantReply.class)));
        assertNotEquals(replay.recordingFile(base), replay.recordingFile(
                new ModelClient.Call<>("assistant", "F01", 1, "system v2", "question", null, AssistantReply.class)));
    }

    public void replayWithoutRecordingSaysHowToRecord() {
        ClaudeClient.ModelException e = expectThrows(ClaudeClient.ModelException.class, () -> ClaudeClient
                .replayOnly("claude-opus-5-5")
                .ask(new ModelClient.Call<>("assistant", "none", 1, "s" + System.nanoTime(), "q", null, AssistantReply.class)));
        assertTrue(e.getMessage().contains("set ANTHROPIC_API_KEY and run again to record"), e.getMessage());
    }
}
