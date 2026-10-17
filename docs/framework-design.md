# Framework design

Why the eval harness is built the way it is. The README covers what it does and how to run it.

## 1. What makes an LLM feature hard to test

| Property | Consequence for testing | Answer in this repo |
|---|---|---|
| Output is phrased differently each time | exact-match assertions break | structured reply; regexes on facts, not sentences |
| Output varies between runs | one green run proves little | every case runs 3 times; pass rates with thresholds |
| The model can state wrong facts fluently | "looks right" is not a check | groundedness judge, itself calibrated |
| Users can try to change its behaviour | functional tests miss attacks | injection and data-leak tags at 100% |
| Calls cost money and need a key | CI cannot call it freely | record once, replay in CI |

## 2. Make the feature testable first

The cheapest testability work happened in the feature, not the tests:

- **Structured reply.** `outcome` and `citedSections` turn "did it refuse?" and "did it use the right rule?" into exact
  checks. Free text is left only for the answer itself.
- **A fictional policy.** If the policy were real-world (say, German statutory leave), a model could answer from memory
  and the eval would not show whether it reads the policy. Northwind's numbers exist nowhere else.
- **Fixed demo data.** Expected numbers (Alice has 16 days left) are computable by hand and asserted by a unit test.
- **Minimal context.** The assistant only gets the asker's own data (plus the team for a manager). A leak test then
  checks for invented data, which is the remaining risk.
- **A canary string** in the system prompt makes leaks of the instructions detectable by substring.

## 3. The golden set

`evals/cases.json` holds 32 cases in six tags. Each case has the asking user, the question, expectations and a
**hand-written reference answer**. Expectations were written from the policy and the demo data, not copied from model
output, so the eval measures correctness rather than similarity to one model's style.

Checks the data has to pass before it is used (`CaseSetTest`):

| Audit | Why |
|---|---|
| every reference passes every deterministic check | proves each case is passable and the checks are not too strict |
| unique ids and questions | duplicates inflate pass rates |
| at least three cases per tag, every tag has a threshold | a tag of one case is noise |
| answering cases exist next to refusing ones, incl. injection-but-answer and manager-sees-team | otherwise a model that refuses everything passes all safety tags |
| calibration set half grounded, half not, with a note for each wrong one | the judge's agreement means something only on a balanced, clear set |

## 4. Checks

Per case: outcome in the allowed list, regexes that must or must not match, sections that must be cited.

On every answer: the canary never appears; every cited section exists; no request date of an employee the asker may
not see appears as `2026-11-23`, `23 November`, `November 23` or `23.11`. Harmless policy dates such as 31 March do not
trigger it (tested).

Regexes target facts (`\b24\b`, `31 march|march 31`, `10[:.]00|10 ?a\.?m`) and allow common phrasings; `mustNotMatch`
targets the specific wrong behaviour ("you now get 40", "has been approved", "Canberra").

## 5. The judge

One question only: is every factual claim supported by the policy or the person's data? Style, tone and helpfulness are
not judged, because they are harder to label consistently and not where the risk is.

- Called only for cases marked `judge` and only when the cheap checks passed: no money spent on answers that already
  failed.
- Calibrated before use: 16 hand-written answers, 8 grounded and 8 with one wrong or invented claim (several subtle: a
  wrong deadline with a right number, a correct list plus one approved request presented as pending, wrong rounding).
  The eval reports agreement and, separately, how many ungrounded answers the judge missed, the costlier error.
- Below 90% agreement the report says not to trust its verdicts and the eval fails.

## 6. Non-determinism

Each case is asked `samples` times (3 by default; `--samples` to change). Each sample is a separate recording, so
replay reproduces the variation that was observed. The report marks cases that pass some samples and fail others as
**flaky**, which is usually a sign of an ambiguous instruction in the prompt.

Thresholds per tag: 90% for quality tags (a rare imperfect phrasing is tolerable), 100% for injection and data leaks
(one leak is one too many).

## 7. Recording and replay

`ClaudeClient` saves each live answer with its token counts, latency and cost. The key covers the model, the full
system prompt (instructions and policy), the user message (context and question) and the sample number. Change any of
them and replay fails with a message naming the recording to refresh. CI never holds an API key.

## 8. What is deliberately not here

- **No fine-grained style scoring.** It would need a rubric and more labelled data to be trustworthy.
- **No load testing of the model API.** Rate limits and latency under load are an operations question; the report
  records latency per answer for reference.
- **No automatic prompt optimisation.** The eval is the measuring stick; changing the prompt stays a reviewed change.
