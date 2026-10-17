package com.niranjankagri.llmtesting;

import com.niranjankagri.llmtesting.app.Policy;
import com.niranjankagri.llmtesting.eval.CaseSet;
import com.niranjankagri.llmtesting.eval.EvalReport;
import com.niranjankagri.llmtesting.eval.EvalRunner;
import com.niranjankagri.llmtesting.eval.Judge;
import com.niranjankagri.llmtesting.eval.JudgeCalibration;
import com.niranjankagri.llmtesting.llm.ClaudeClient;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertTrue;

/**
 * The eval as a test suite: `mvnw test -Dsuite=eval`. Uses the real ClaudeClient, so it replays
 * recordings (CI) or calls Claude live when a key is set. One test for the judge's calibration and one
 * per tag threshold, so a CI failure names what regressed; details are in target/eval/report.md.
 */
@Test(groups = "eval")
public class EvalSuiteTest {

    private JudgeCalibration.Result calibration;
    private EvalRunner.Run run;

    @BeforeClass
    public void runEval() {
        ClaudeClient model = ClaudeClient.fromEnvironment();
        Policy policy = Policy.load();
        calibration = JudgeCalibration.run(new Judge(model, policy), JudgeCalibration.load(JudgeCalibration.DEFAULT));
        run = new EvalRunner(model, policy).run(CaseSet.load(CaseSet.DEFAULT));
        System.out.println("Eval report: " + EvalReport.write(run, calibration));
    }

    public void judgeAgreesWithTheHandLabels() {
        assertTrue(calibration.trusted(), String.format("judge agreement %.0f%% is below %.0f%%",
                calibration.agreement() * 100, calibration.minAgreement() * 100));
    }

    @DataProvider
    public Object[][] tags() {
        return new Object[][] {{"policy_fact"}, {"personal_data"}, {"not_in_policy"}, {"out_of_scope"},
                {"injection"}, {"data_leak"}};
    }

    @Test(dataProvider = "tags", dependsOnMethods = "judgeAgreesWithTheHandLabels")
    public void tagMeetsItsThreshold(String tag) {
        EvalRunner.TagResult t = run.tags().stream().filter(r -> r.tag().equals(tag)).findFirst().orElseThrow();
        assertTrue(t.met(), String.format("%s: %d of %d answers passed (%.0f%%), threshold %.0f%%; see target/eval/report.md",
                tag, t.passed(), t.total(), t.rate() * 100, t.threshold() * 100));
    }
}
