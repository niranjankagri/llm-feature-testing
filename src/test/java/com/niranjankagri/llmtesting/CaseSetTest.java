package com.niranjankagri.llmtesting;

import com.niranjankagri.llmtesting.app.AssistantReply.Outcome;
import com.niranjankagri.llmtesting.app.Employees;
import com.niranjankagri.llmtesting.app.Policy;
import com.niranjankagri.llmtesting.eval.CaseSet;
import com.niranjankagri.llmtesting.eval.CaseSet.EvalCase;
import com.niranjankagri.llmtesting.eval.Checks;
import com.niranjankagri.llmtesting.eval.JudgeCalibration;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Audits the eval data itself before any model sees it: an eval with a broken case or an unpassable
 * check produces confident numbers that point the wrong way.
 */
@Test(groups = "unit")
public class CaseSetTest {

    private static final CaseSet SET = CaseSet.load(CaseSet.DEFAULT);
    private static final Policy POLICY = Policy.load();

    /** One row per eval case, named by its id. */
    @DataProvider
    public Object[][] cases() {
        return SET.cases().stream().map(c -> new Object[] {c.id(), c}).toArray(Object[][]::new);
    }

    /**
     * Every hand-written reference answer passes every deterministic check: each case can be passed, and no check
     * is stricter than intended.
     */
    @Test(dataProvider = "cases")
    public void referenceAnswerPassesEveryDeterministicCheck(String id, EvalCase c) {
        List<Checks.Result> failed = Checks.run(c, c.reference(), POLICY).stream().filter(r -> !r.passed()).toList();
        assertTrue(failed.isEmpty(), id + ": the hand-written reference fails " + failed);
    }

    /**
     * Each case is usable: the user exists, the tag has a threshold, there is an expected outcome, every regex
     * compiles, cited sections exist, and the judge is only asked about answers with content.
     */
    @Test(dataProvider = "cases")
    public void caseIsWellFormed(String id, EvalCase c) {
        assertTrue(Employees.find(c.user()).isPresent(), id + ": unknown user");
        assertTrue(SET.thresholds().containsKey(c.tag()), id + ": tag without threshold " + c.tag());
        assertTrue(!c.expect().outcomes().isEmpty(), id + ": no expected outcome");
        c.expect().mustMatch().forEach(Pattern::compile);
        c.expect().mustNotMatch().forEach(Pattern::compile);
        assertTrue(POLICY.sectionIds().containsAll(c.expect().mustCite()), id + ": cites a section that does not exist");
        assertTrue(!c.expect().judge() || c.expect().outcomes().contains(Outcome.ANSWERED)
                        || c.expect().outcomes().contains(Outcome.NOT_IN_POLICY),
                id + ": judging groundedness only makes sense for answers with content");
    }

    /** No duplicate ids or questions: duplicates would inflate pass rates. */
    public void idsAndQuestionsAreUnique() {
        Set<String> ids = new HashSet<>();
        Set<String> questions = new HashSet<>();
        for (EvalCase c : SET.cases()) {
            assertTrue(ids.add(c.id()), "duplicate id " + c.id());
            assertTrue(questions.add(c.user() + "|" + c.question().toLowerCase()), "duplicate question " + c.id());
        }
    }

    /** Every tag has at least three cases, and the tags in the cases are exactly the tags with thresholds. */
    public void everyTagHasAtLeastThreeCases() {
        Map<String, Long> perTag = SET.cases().stream().collect(Collectors.groupingBy(EvalCase::tag, Collectors.counting()));
        assertEquals(perTag.keySet(), SET.thresholds().keySet());
        perTag.forEach((tag, n) -> assertTrue(n >= 3, tag + " has only " + n + " cases"));
    }

    /**
     * A model that refuses everything cannot pass: the set needs enough answer cases, a manager allowed to see
     * their team, and an injection the assistant must answer correctly despite.
     */
    public void setTestsBothAnsweringAndRefusing() {
        // An assistant that refuses everything would pass every injection and leak case: the set must
        // also hold cases where the right behaviour is to answer, including a manager about their team.
        long declines = SET.cases().stream().filter(c -> c.expect().outcomes().equals(List.of(Outcome.DECLINED))).count();
        long answers = SET.cases().stream().filter(c -> c.expect().outcomes().equals(List.of(Outcome.ANSWERED))).count();
        assertTrue(declines >= 5 && answers >= 10, "declines " + declines + ", answers " + answers);
        assertTrue(SET.cases().stream().anyMatch(c -> c.tag().equals("data_leak") && c.user().equals("maria")
                && c.expect().outcomes().contains(Outcome.ANSWERED)), "no 'manager may see the team' case");
        assertTrue(SET.cases().stream().anyMatch(c -> c.tag().equals("injection")
                && c.expect().outcomes().contains(Outcome.ANSWERED)), "no 'answer despite the injection' case");
    }

    /** The calibration set is half grounded and half not, and every ungrounded item says what is wrong. */
    public void calibrationSetIsBalancedAndClear() {
        JudgeCalibration.CalibrationSet cal = JudgeCalibration.load(JudgeCalibration.DEFAULT);
        long grounded = cal.items().stream().filter(JudgeCalibration.Item::grounded).count();
        assertEquals(grounded * 2, cal.items().size(), "half grounded, half not");
        cal.items().stream().filter(i -> !i.grounded()).forEach(i ->
                assertTrue(i.note() != null && !i.note().isBlank(), i.id() + ": an ungrounded item must say what is wrong"));
    }
}
