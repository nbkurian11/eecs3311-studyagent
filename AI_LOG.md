# AI Usage Log

Record of how AI tools were used during Stage 2 of StudyBetter. Each entry lists what was asked, what the AI
produced, what I checked, and the resulting commit. I reviewed, ran and tested every change before committing it.
No API keys appear in this log.

## Tools and cost

| Tool | Model | Used for | Token and money cost |
|---|---|---|---|
| Claude (Cowork, Claude desktop app) | Claude Opus 5.5 | Oct 25 to Nov 2: project setup, domain classes, DataStore, LLM layer, services, AgentController. Later: project brief for Claude Code, README review, demo script, updated class diagram, report drafting | Claude Pro subscription, CAD $28/month, shared by both Claude tools. Flat rate, so tokens are not billed or tracked per token |
| Claude Code (terminal, Windows) | Claude Opus 5.5 (default model) | Nov 9 to 14: CLI, agent and tools, F01/F02/F05 to F08, F11, F12, Swing GUI, Gemini fixes, end-to-end verification | Same Claude Pro subscription, no extra cost |
| Google Gemini API (runtime LLM inside the app) | gemini-3.8-flash (gemini-3.5-flash also used) | The app's AI features, not a development tool | Free tier, $0 |

---

## 2026-10-25 to 2026-11-02: Periods 1 and 2, foundation (Claude Cowork)

**Tool:** Claude Cowork (Claude Opus 5.5)

I gave Claude my Stage 1 design and built the foundation one class or service at a time. For each step I
read the code, ran it in IntelliJ, and committed only after it worked.

| Prompt (summary) | Result | My review / changes | Commit |
|---|---|---|---|
| Set up a Maven project with a `.env` config loader and entry point | `pom.xml` (Java 21, Gson), `Config`, `Main` | Fixed a stray character in `pom.xml`; rewrote `.gitignore` as ASCII after git could not read it and confirmed `.env` was ignored with `git check-ignore` | `de37ff2` |
| Create the domain classes from my Stage 1 class diagram | `Course`, `Material`, `AssessmentItem`, `Topic` | Renamed files that did not match class names (`AssesmentItem.java`) | `c76ee34` |
| DataStore singleton that saves to JSON | Private constructor, `getInstance()`, Gson save/load, date adapters | Ran it twice to confirm data persisted and the same instance was returned | `348db1a` |
| LLM layer: Strategy interface, Gemini adapter, mock | `LLMClient`, `GeminiClient` (REST via `java.net.http`), `MockLLMClient`, `LLMException` | Chose the REST API over the Google SDK to avoid an extra dependency | `7641b32` |
| CourseManager with duplicate-course check (F01) | `CourseManager` | Tested adding a duplicate course code | `4fd6b9d` |
| Observer base classes and deadline tracking (F03) | `Subject`, `DataObserver`, `DeadlineTracker` | Tested overdue, due-soon and undated items | `1e3a2d8` |
| GradeBook with weighted grade and required score (F04) | `GradeBook` (extends `Subject`) | Checked the math by hand | `d063aba` |
| Study session logging (F10) | `SessionLogger`, `StudySession` | Confirmed sessions persist | `8df7cf9` |
| Concept mastery tracking (F09) | `MasteryTracker`, `ConceptMastery` | Checked the weighted update and labels | `a0d5a30` |
| Facade over all services | `AgentController` | Confirmed the UIs would only call the controller | `fd1ec38` |

Claude then helped me write a project brief (`CLAUDE.md`, in the repository) describing the architecture, class names,
patterns and rules for Claude Code: no keys in git, one feature per commit, readable code with class-level Javadoc.

---

## 2026-11-09: Step 1, interactive CLI

**Tool:** Claude Code (Claude Opus 5.5)
**Commit:** `5a81a2f` Add interactive CLI (CLIApp) and launch it from Main

**What I asked for**
- Read `CLAUDE.md` and the `src` folder, then make sure `CLIApp` and `Main` compile.
- When it turned out `ui/CLIApp.java` was not on disk (CLAUDE.md wrongly said it existed locally), I asked
  Claude to write it from scratch with these commands: help, courses, add-course, delete-course, add-item,
  deadlines, complete, grade, grade-status, start, end, mastery, quit. Inputs use prompts, assessments are
  picked from a numbered list, and `IllegalArgumentException` / `IllegalStateException` print as "Error: ...".
- I also asked to update `Main` to launch the CLI, with a `--gui` flag that prints "GUI not built yet" for now.

**What the AI did**
- Found that `CLIApp.java` was missing and that `Main.java` was still the old MasteryTracker demo, which
  wiped saved courses and mastery data every time it ran.
- Wrote `ui/CLIApp.java`. It only calls the `AgentController` facade. It turns bad numbers and dates into clear
  errors and asks for confirmation before deleting a course.
- Replaced `Main` with a small launcher (CLI by default, `--gui` placeholder).

**How it was verified**
- `mvn` is not on PATH, so I compiled all sources with `javac --release 21` against Gson 2.11.0: no errors.
- Ran every command in a scripted run from a scratch folder (so the real `data-old` was not touched), including
  error cases: bad date, out-of-range pick, `end` with no session, starting a second session, unknown course,
  unknown command, cancelled delete. Everything behaved as expected.
- Checked `git status` before committing: `.env` and `data-old` were not staged.

**Notes / follow-ups**
- Entering a grade also marks the item completed (existing `AssessmentItem.setMark` behaviour).
- Undated items print as "due No date" (existing `DeadlineTracker.Deadline.toString`).
- `Main` should switch to GUI-by-default with `--cli` once the Swing `MainWindow` exists (step 6).

---

## 2026-11-09: Step 2, study agent, F05 explain and F01 upload with AI summary

**Tool:** Claude Code (Claude Opus 5.5)
**Commits:** `70035c2` Add agent tools, retrieval and material text for F01/F05;
`9d8a7cf` Add StudyAgent with F05 explain and F01 summaries; harden Gemini calls (agent part)

**What I asked for**
- Step 2 from `CLAUDE.md`: the `agent` package (`Tool`, `ToolRegistry`, `MaterialSearchTool`, `DeadlineTool`,
  `StudyAgent`), F05 explain a concept, F01 upload a material with an AI summary, the matching
  `AgentController` methods and the CLI commands `upload-material` and `explain`.
- A scripted smoke test in a scratch folder using `MockLLMClient`, and instructions for testing with my real key.
- Follow-up: uploads made on the offline mock should count as "summary pending", so `summarize` can fix
  them once a key is set (the mock was saving its placeholder text as if it were a real summary).

**What the AI did**
- `MaterialSearchTool` does keyword retrieval: it splits each material into passages, scores them by distinct
  keyword matches and returns the best ones. `StudyAgent.explain` sends those passages to the LLM as context
  and asks for `[Source n]` citations. With no matching passages it gives a general answer labelled
  "not from your course materials". If the LLM fails, it returns the passages themselves.
- `StudyAgent` depends only on `LLMClient` (Strategy) and implements `DataObserver` (Observer), keeping a short
  memory of recent events for the study planner later.
- `Material` now stores its text and a `summaryPending` flag. `CourseManager.readMaterialFile` accepts
  .txt/.md files (UTF-8, up to 2 MB) and gives clear errors for PDFs and missing files.
- `AgentController` adds `uploadMaterial`, `getMaterials`, `retryPendingSummaries` and `explainConcept`. The CLI
  adds `upload-material`, `materials`, `summarize` and `explain`, strips quotes from "Copy as path" and warns
  at startup when running on the mock.
- Added a missing class-level Javadoc to `AgentController` (Facade).

**How it was verified**
- `mvn -q compile` with IntelliJ's bundled Maven (`mvn` is not on PATH).
- Smoke test run from a scratch folder (so the real `data-old` was not touched). It checked: the summary is stored;
  the prompt sent to the LLM contains the matching passage but not an unrelated one; sources are listed;
  general answers are labelled; when the LLM fails, uploads are still saved as pending and explain falls back to
  passages; retry fills pending summaries; data survives a save and reload; bad paths and unknown courses are
  rejected. Also drove the real CLI with scripted input.
- Before committing, checked that commit `70035c2` compiles on its own (in a temporary git worktree).

**Notes / follow-ups**
- `StudyAgent`, `AgentController` and `CLIApp` also had reliability changes, so they went into the second commit.
  The first commit holds the tools, domain changes and `CourseManager`.
- PDF text extraction is not supported yet; export PDFs to .txt first.

---

## 2026-11-09: Gemini reliability (retry, error reasons, new default model)

**Tool:** Claude Code (Claude Opus 5.5)
**Commit:** `9d8a7cf` Add StudyAgent with F05 explain and F01 summaries; harden Gemini calls (reliability part)

**Problems I hit when testing with my real Gemini key**
- **HTTP 404 / model unavailable:** the old default model `gemini-2.5-flash` failed. Gemini said it is no longer
  available to new users and recommended `gemini-3.8-flash`. Retrying does not help with this kind of error.
- **HTTP 503 (high demand):** Gemini sometimes rejected requests because the model was overloaded. The app
  failed on the first try, and the CLI showed only a generic "the AI service is unavailable" for
  `summarize`, or the raw JSON error body, instead of a short reason.

**What I asked for**
- Change the default model to `gemini-3.8-flash` in `GeminiClient` and `.env.example` (not `.env`).
- Retry HTTP 503 and 429 up to 3 times with 1s/2s/4s backoff before throwing `LLMException`, but not 400/404.
- Make `summarize` and `explain` print the actual reason (e.g. "HTTP 503: high demand").

**What the AI did**
- `GeminiClient.generate` now loops: 200 returns the text; 429/503 wait 1s, 2s, 4s and retry; anything else, or a
  4th temporary failure, throws `LLMException("HTTP <code>: <reason>")`, adding "(after 3 retries)" when it
  retried. The reason is Gemini's `error.message` field, cut at 200 characters.
- Added a constructor that takes the endpoint and the retry delays, so tests can use a local fake server.
- `retryPendingSummaries` returns a result per material with its error reason; the CLI prints
  "Still pending '<title>': <reason>". `explain` starts with "AI request failed: <reason>".

**How it was verified**
- Ran the smoke tests against a local fake Gemini server (JDK `HttpServer`) that returns a set sequence of statuses:
  503, 503 then 200 succeeds on the 3rd call; 429 then 200 succeeds on the 2nd; four 503s give up after 4 calls with
  the expected message; 404 and 400 fail after 1 call with no retry. A run with the real delays took 3010 ms (1s + 2s).
  The real reasons reach `explain`, `upload-material` and `summarize`. All 26 checks passed, and the 17 earlier
  checks still pass.
- Checked that `.env` was not modified or staged; `.env.example` holds only placeholders.

**Notes / follow-ups**
- While Gemini returns 503/429, a call can now take about 7 seconds before failing. The GUI (step 6) must run
  LLM calls in a `SwingWorker`.

---

## 2026-11-09: Step 3, F02 syllabus import

**Tool:** Claude Code (Claude Opus 5.5)
**Commit:** `3ac2a79` Add F02 syllabus import with review-before-save

**What I asked for**
- Step 3 from `CLAUDE.md`: `StudyAgent.extractSyllabus(text)` asks the LLM for strict JSON (assessments: title,
  dueDate YYYY-MM-DD or null, weight; topics: name, week), parses it with Gson and validates it (warn if weights
  don't total 100, highlight missing dates).
- `importSyllabus` in `AgentController` and an `import-syllabus` CLI command that reads a .txt/.md file, shows the
  extracted items for review and saves them only after I confirm (y/n). If the LLM fails, offer manual entry.
- A scripted smoke test with a fake LLM response, and instructions for testing with my real key.

**What the AI did**
- New `domain/SyllabusDraft`: the extracted assessments, topics and warnings. Nothing is saved until I confirm.
- `StudyAgent.extractSyllabus` strips ```json fences and any text around the JSON object, parses it with Gson and
  retries once with a firmer reminder if the reply is not valid JSON. Validation skips entries with no title and
  duplicates, leaves unreadable dates blank, sets missing or out-of-range weights to 0, accepts values like "10%"
  and "Week 2", warns when weights don't total 100 (±0.5) and lists every assessment with no due date.
- `AgentController.importSyllabus` returns a preview without saving and flags titles the course already has;
  `saveSyllabus` saves after confirmation and skips duplicates, so importing twice doesn't double anything.
  LLM failures come back in the result instead of crashing.
- `CourseManager`: the .txt/.md checks moved into a shared `readTextFile` used by materials and syllabi;
  `importSyllabus` adds items and topics with a single save.
- CLI: `import-syllabus` (review, y/n, manual-entry fallback reusing the `add-item` prompts) and `topics`.

**How it was verified**
- `mvn -q compile` with IntelliJ's bundled Maven.
- Drove the real `CLIApp` from a scratch folder with a scripted fake LLM: fenced JSON with a "10%" weight, an
  "Oct 22" date, a null date, an untitled entry and weights totalling 95% (all warnings shown; after `y` the items
  appear in `deadlines` and `topics`); re-import (all flagged as duplicates, `n` saves nothing); two bad replies
  (manual entry, a bad weight rejects only that item); one bad reply then valid JSON (retry works); missing file.
- Checked `git status` before committing: `.env`, `data-old` and `AI_LOG.md` were not staged.

**Bug found by the smoke test**
- The first run failed every import with `Error: Conversion = s, Flags =  `. The prompt is a text block filled in
  with `String.formatted(...)`, and the rule "weight is a number ... without a % sign" contains `% s`, which Java
  reads as a format specifier (`%s` with a space flag). Fixed by escaping it as `%%`. The syllabus text itself is
  passed as an argument, so a `%` in a real syllabus cannot cause this.

**Issue: Gemini request timeout (found with my real key)**
- I ran `import-syllabus` on EECS3311 with a small syllabus (about 3 lines, roughly 300 characters: grading
  breakdown plus weekly topics). The CLI hung for the full 60 s request timeout, then printed
  "Could not extract the syllabus automatically (Could not reach Gemini: request timed out)." and offered manual
  entry (I answered `n`). Earlier in the same session Gemini also returned HTTP 503 "This model is currently
  experiencing high demand", so the model seemed overloaded rather than the request being too big.
- Cause: `GeminiClient` retried 503/429 but treated a timeout as a hard failure, and 60 s was too short when the
  model is busy.
- While checking the fix, a minimal direct request to `gemini-3.8-flash` timed out at 120 s four times and got
  two more 503s, which confirms the overload.

**Fix applied (Gemini timeout and thinking level)**
- Request timeout raised from 60 s to 120 s.
- A timeout is now treated like a 503: it is retried once (after 1 s). If it times out again, the error is
  "Could not reach Gemini: request timed out after 120s (after 1 retry). The model may be overloaded; try again later."
- Lower thinking level for faster replies: requests now send
  `"generationConfig":{"thinkingConfig":{"thinkingLevel":"low"}}`. The docs list low/medium/high for
  `gemini-3.8-flash` (default medium; "minimal" is not supported). I checked against the real API: `"minimal"` is
  rejected with HTTP 400 "Thinking level MINIMAL is not supported for this model", which shows the field is read,
  and `"low"` passed validation (the replies were 503/timeouts because of the overload, so I have not yet seen a
  200 with it). As a safety net, if Gemini rejects the thinking level with a 400, the client resends once without
  it. It is configurable with `GEMINI_THINKING_LEVEL` in `.env` (blank uses the model default); `.env.example`
  documents it with no secrets.
- Tests: re-ran the earlier 26 checks (all pass) and added 5 against a local fake server with a 300 ms timeout:
  timeout then 200 succeeds on the 2nd call; two timeouts give up after 1 retry with the message above; the request
  body contains `thinkingLevel: low`; a blank setting sends no `generationConfig`; a 400 about the thinking level is
  resent without it and succeeds. 31/31 pass. The step 3 CLI smoke test still passes.

**Notes / follow-ups**
- Worst case, an unresponsive model now takes about 4 minutes (two 120 s timeouts) before the CLI offers manual
  entry. That is acceptable for the CLI but the GUI (step 6) must run LLM calls in a `SwingWorker`.
- Re-test `import-syllabus` with the real key when Gemini is less busy to confirm a 200 with `thinkingLevel: low`.

---

## 2026-11-09: Step 4, F06 quiz and F07 mock exam generation

**Tool:** Claude Code (Claude Opus 5.5)
**Commit:** `f4e0a09` F06 quiz and F07 mock exam generation with Template Method and Simple Factory

**What I asked for**
- Step 4 from `CLAUDE.md`: `PracticeSettings`, `Question` (text, options, answer, topic, difficulty),
  `PracticeAssessment` as a Template Method (`build()` runs `applySettings()`, then abstract `selectTopics()`, then
  abstract `generateQuestions()`), `Quiz` (chosen or weak topics, N questions), `MockExam` (questions spread by
  syllabus topic weights, time limit) and `AssessmentFactory` as a Simple Factory (`create(type, settings)`).
- Questions come from the LLM as strict JSON, are validated, and the request is retried once on a bad format.
- `createPractice` in `AgentController`, plus CLI `quiz` / `mock-exam` commands that show the questions, let me
  answer them and save the answers for step 5 grading.
- A smoke test with a fake LLM and instructions for testing it myself. No commit until I had reviewed it.

**What the AI did**
- `PracticeAssessment.build` is `final`, so the step order cannot change. `applySettings` checks 1-30 questions and
  easy/medium/hard; `MockExam` adds a time-limit check (1-300 min).
- `Quiz` uses the chosen topics (by number or name, spelled as in the course) or my weak topics from
  `MasteryTracker`, with the questions split evenly in one LLM request.
- `MockExam` covers all syllabus topics (optionally only up to a given week). Questions are split by topic weight
  using largest remainders; topics with no weight share the percent left over, and with no weights at all the split
  is even (with a warning). It generates in batches of up to 8, so if one batch fails I am offered a shorter exam.
- New `QuestionGenerator` interface, implemented by `StudyAgent`, so `Quiz`/`MockExam` do not depend on the LLM.
  `StudyAgent.generateQuestions` retrieves passages per topic (warns when a topic has no materials), asks for JSON,
  validates each question (drops malformed ones, caps extras per topic, matches topic names, accepts "B", "b)" or the
  option text as the answer), retries once, and shuffles the options so the answer is not always A.
- Submitted practice is saved to `DataStore` (`PracticeManager`); `PracticeAssessmentAdapter` lets Gson load it back
  as the right `Quiz`/`MockExam` subclass. New CLI command `practices` lists it as "awaiting grading".
- In the CLI, questions show one at a time, a blank skips, an invalid letter asks again. For mock exams the time left
  is shown, and an answer typed after the limit is not counted and the earlier answers are submitted.

**Design change: topic weights**
- `Topic` had no weight, so a mock exam had nothing from the syllabus to weight by. `Topic` got an optional
  `weight` (null when not stated), and `extractSyllabus` now asks for a topic's weight only when the syllabus states
  it (never guessed; values outside 0-100 are ignored). Most syllabi give none, so the even split is the common case.

**How it was verified**
- `mvn -q compile` with IntelliJ's bundled Maven.
- Smoke test with a fake LLM, run from a scratch folder so the real `data-old` was not touched: 43 checks passed,
  covering the 5/3/1/1 weighted split and week filter, capping extra questions, dropping a malformed one, MCQ answers
  resolving to the right option after shuffling, retrieval context being used when notes exist, one retry then an
  error after two bad replies, a partial mock exam when one batch fails, weak-topic quizzes, every validation error,
  and saving then reloading answers as `Quiz`/`MockExam`. Also drove the real `CLIApp` through `quiz`, `mock-exam`,
  `practices` and error cases with scripted input.
- Checked `git status` before committing: `.env`, `data-old` and `AI_LOG.md` were not staged.

**Notes / follow-ups**
- Not tested automatically: the timer running out (needs a real minute). To test by hand: `mock-exam` with a
  1-minute limit, wait, then answer.
- The CLI cannot interrupt a typed answer, so time-up is detected when the answer is entered, not live.
- Needs a real Gemini key; on the offline mock `quiz`/`mock-exam` print a clear "no GEMINI_API_KEY" error.
- Step 5 will grade the saved answers (`submitAnswers`) and update mastery.

---

## 2026-11-09: Issue, quiz failed with a Gemini timeout (gemini-3.8-flash overloaded)

**Tool:** Claude Code (Claude Opus 5.5)
**Commit:** none (configuration change in my local `.env` only)

**What happened**
- Testing step 4 with my real key, `quiz` on EECS3311 (Observer Pattern, Singleton Pattern, 4 easy questions)
  waited about 4 minutes and then printed "Could not reach Gemini: request timed out after 120s (after 1 retry).
  The model may be overloaded; try again later."

**How it was diagnosed**
- `.env` was read correctly (model `gemini-3.8-flash`, thinking level `low`).
- Listing models with my key returned HTTP 200 in 0.2 s, so the key and network were fine.
- A one-word request sent with curl (no app code) to `gemini-3.8-flash` hit a 90 s timeout twice.
- Other models with the same tiny request: `gemini-3.7-flash` HTTP 503 "high demand" after 29 s;
  `gemini-3.5-flash` OK in 2.8 s; `gemini-3.1-flash-lite` OK in 8 s; `gemini-flash-latest` timed out at 30 s.
- Ran the same quiz through the real app code on `gemini-3.5-flash` from a scratch folder: 12.6 s, 3 multiple choice
  and 1 short answer, correctly tagged, with the right answers on different letters.
- Conclusion: Google's `gemini-3.8-flash` was overloaded (same as during step 3); step 4 had no bug. The app did
  what it was designed to do (120 s timeout, one retry, clear error).

**Fix**
- I changed `GEMINI_MODEL` in my `.env` to `gemini-3.5-flash` (not committed; `.env.example` still shows
  `gemini-3.8-flash`). I can switch back when 3.8 is less busy.
- Possible follow-up offered but not done: an optional `GEMINI_FALLBACK_MODEL` in `GeminiClient` to retry on a
  second model after timeouts or repeated 503s.

---

## 2026-11-09: Step 5 (first half), F08 practice grading and past-test analysis

**Tool:** Claude Code (Claude Opus 5.5)
**Commit:** `98b0c6d` F08 practice grading and past-test analysis

**What I asked for**
- Only the F08 half of step 5: grade saved practice attempts. Multiple choice graded deterministically, short
  answers graded by the LLM with feedback, a `GradeResult` with per-question results tagged by topic passed to
  `MasteryTracker.update()`, and the attempt marked graded.
- `gradePractice` in `AgentController`, a `grade-practice` CLI command listing ungraded attempts, and automatic
  grading right after a quiz or mock exam.
- `analyzePastTest(courseCode, path)` with an `analyze-past-test` command for a .txt past test: the LLM extracts
  questions, marks and topics as JSON, then explains lost marks. Show the score, per-question feedback and the
  updated mastery. If the LLM fails, short answers are marked pending.
- Compile, smoke test with a fake LLM, testing instructions, no commit until I had reviewed it.

**What the AI did**
- `StudyAgent.gradePractice`: multiple choice checked against the key; blank answers score 0 with the correct answer
  shown and no LLM call; short answers sent in one LLM request per attempt for a score of 1, 0.5 or 0 and feedback.
  Wrong multiple choice answers go in the same request for an explanation only (F08 says each mistake is explained);
  their score stays deterministic. One retry on a bad reply.
- LLM failure or offline mock: short answers are marked pending with the reason, multiple choice is still graded.
  Running `grade-practice` again grades only the pending answers, and `GradeResult.newlyGradedSince` makes sure only
  newly graded answers reach `MasteryTracker`, so multiple choice is not counted twice.
- `analyzePastTest` works in two LLM steps: extract questions, answers, marks and topics as JSON (validated, matched
  to course topic names, questions without readable marks skipped with a warning), then explain lost marks using
  passages retrieved from my materials. If the explanation step fails, the marks are kept. Following UC08, the CLI
  shows the result and only updates mastery after I confirm (y/n).
- `GradeResult.QuestionResult` now holds the question, my answer, the correct answer, points out of max points
  (partial credit), feedback and a pending flag; `GradeResult` adds totals, a per-topic breakdown and the graded
  timestamp. `ConceptMastery.record(double)` accepts partial credit (2/5 counts as 0.4).
- The result is saved on the attempt (`PracticeAssessment.setResult`), so scores and pending answers survive a
  restart. `practices` shows scores; the grade report shows each verdict, answers, feedback, a by-topic table and the
  updated mastery.

**How it was verified**
- `mvn -q compile` with IntelliJ's bundled Maven.
- Smoke test with a fake LLM from a scratch folder (real `data-old` untouched): 45 checks passed. Covered: score 2.5/4
  with partial credit, one LLM call per attempt with the right MCQ not sent, explanation added to the wrong MCQ,
  attempt marked graded and grading twice rejected, LLM failure leaving 2 short answers pending while MCQ is scored,
  regrade sending only the pending ones without double-counting mastery, offline mock making no LLM call, one retry
  on a bad reply, an item the LLM skipped staying pending, blank answers, results surviving a reload, past test
  7/13 with Q3 skipped, topics matched, explanations using material as context, mastery unchanged until confirmed,
  partial credit applied after confirming, explanation failure keeping marks, extraction retry and error, missing
  file. Also drove the real `CLIApp` through `quiz` (auto-grading), `grade-practice`, `analyze-past-test` (y and n),
  `practices` and `mastery`.
- Checked `git status` before committing: `.env`, `data-old` and `AI_LOG.md` were not staged.

**Notes / follow-ups**
- Not built (optional in the Stage 1 spec): self-grading of pending short answers, and flagging a disputed grade to
  exclude it from mastery.
- Past tests must be text (.txt/.md); PDF or image input is not supported.
- Not yet tested with the real Gemini key at the time of this entry.
- Next: the F12 half of step 5 (`buildStudyPlan`).

## 2026-11-09: Step 6, Swing GUI and F11 Progress Dashboard

**Tool:** Claude Code (Claude Opus 5.5)
**Commit:** `af3bfbd` Add Swing GUI with all tabs and F11 ProgressDashboard (Observer)

**What I asked for**
- `Main`: GUI by default, `--cli` for the CLI.
- `ui/MainWindow` (JFrame + JTabbedPane) using only `AgentController`, with tabs for Courses (add/delete, upload
  material with a file chooser, summaries, syllabus import with a review dialog), Deadlines, Grades, Learn,
  Practice (generate, answer in the window, submit, auto-grade, past attempts), Mastery, Sessions and Dashboard.
- F11: `service.ProgressDashboard` implementing `DataObserver`, attached via `AgentController.addObserver`, so the
  Dashboard tab refreshes automatically on grade, session and mastery changes. Bar charts drawn in plain Swing.
- Every LLM call in a `SwingWorker` with a "Working..." status; errors in a `JOptionPane`.
- Compile, headless sanity check where possible, testing instructions per tab, no commit until I had reviewed it.

**What the AI did**
- `MainWindow` holds the tabs, a status bar with a progress indicator, and `runInBackground` (SwingWorker, one task
  at a time, exceptions shown as dialogs). It shows a warning banner when running on the offline mock. Each tab
  extends a small `TabPanel` base class (shared helpers, `refresh()` called whenever the tab is opened).
- `ProgressDashboard` is an observer on both sides: it listens to GradeBook, MasteryTracker, SessionLogger and
  DeadlineTracker, recomputes an immutable `Stats` snapshot (minutes and current grade per course, five weakest
  topics, upcoming and completed deadlines), then notifies the Dashboard tab, which redraws on the Swing thread via
  `invokeLater` (grading runs on a background thread).
- `DeadlineTracker` now extends `Subject` and notifies "deadline" on `markComplete`, plus `getCompleted()`, so
  completed deadlines also refresh the dashboard. `AgentController` gained `getDashboard`, `refreshDashboard`,
  `addDashboardObserver` and `getSessions`.
- Practice tab: radio buttons for multiple choice, text boxes for short answers, a countdown for mock exams that
  submits automatically at 0, and a report with per-question feedback, per-topic score and updated mastery.
- `BarChart` is a small custom JComponent (no new dependency). `Main` falls back to the CLI when there is no display.

**How it was verified**
- `mvn -q compile` with IntelliJ's bundled Maven.
- A harness run from a scratch folder (real `data-old` untouched) filled sample data through the controller, opened the
  window and saved a screenshot of every tab; I reviewed them. With the Dashboard open, entering a grade refreshed it
  ("after: grade") and marking an item complete refreshed it again ("after: deadline").
- Practice flow driven with a fake LLM returning question JSON: generate, answer, submit, auto-grade (1/2), attempt
  listed in Past attempts, mastery updated and the dashboard received a "mastery" event.
- `--cli` still starts the CLI. Checked `git status` before committing: `.env`, `data-old` and `AI_LOG.md` were not staged.

**Notes / follow-ups**
- Not in the GUI yet: analyze-past-test (F08) and the Planner tab (F12, step 5 second half, not built yet).
- Real Gemini calls, the file chooser and the syllabus review dialog were not tested by hand at the time of this entry.
- Next: F12 `buildStudyPlan`, then a Planner tab, then the README (step 7).

## 2026-11-09: Step 5 (second half), F12 study plan, Planner tab and past-test analysis in the GUI

**Tool:** Claude Code (Claude Opus 5.5)
**Commit:** `62cc10c` Add F12 study plan agent, Planner tab and past-test analysis in GUI

**What I asked for**
- `StudyAgent.buildStudyPlan(hoursPerDay)` as a multi-step agent: DeadlineTool for upcoming deadlines and weights,
  MasteryTracker weak concepts, current grades and recent sessions, rank priorities (urgent + high weight + weak
  topics), then a strict JSON weekly schedule from the LLM giving a `StudyPlan` of
  `StudyTask(date, course, description, minutes, reason)`, respecting the hours available per day.
- A deterministic fallback sorted by deadline x weight when the LLM fails, saying so.
- The current plan marked outdated when grades, mastery, sessions or deadlines change (StudyAgent already observes).
- `generateStudyPlan` in `AgentController`, a `plan` CLI command, and a Planner tab in the GUI (hours input,
  SwingWorker, task table with reasons, outdated notice).
- Also: analyze-past-test in the GUI Practice tab, and `exit` working like `quit` in the CLI.
- Compile, smoke test with a fake LLM, testing instructions, no commit until I had reviewed it.

**What the AI did**
- Added `ProgressTool` (grades, weak concepts, study minutes in the last 7 days per course) to the `ToolRegistry`,
  so the agent gets all planning facts through tools, alongside `DeadlineTool`.
- `buildStudyPlan` has four steps: gather facts with tools; rank priorities with `PlanScheduler.rank`
  (score = weight x 7 / (days left + 1), x1.25 for weak topics, x1.2 for a grade under 70%, x1.1 for under 60 min of
  study this week; weak topics also become priorities; deadlines over 21 days away are left out with a note);
  ask the LLM for JSON, including the agent's memory of recent data changes, validate each task (date in the week,
  known course code, at least 15 min) and retry once on a bad reply; trim any day over the limit.
- Fallback (`PlanScheduler.fallbackSchedule`): each priority gets a share of the week in proportion to its score,
  days are filled in score order, never after the day before an assessment is due. Used when the LLM fails, returns
  two unusable replies, or only the offline mock is configured; the plan's notes explain why.
- New domain classes `StudyPlan`, `StudyTask` (record) and `StudyPriority` (record, with a plain-language reason).
  `StudyAgent.onDataChanged` marks the current plan outdated and records which change caused it.
- CLI `plan` prints priorities and each day's tasks with a "why" line, and warns when the previous plan is outdated.
- Planner tab: hours spinner, Generate in a SwingWorker, task table (tooltips for long text), priorities and notes,
  and a live yellow "outdated" notice (the panel is attached as an observer through `AgentController.addObserver`).
- Practice tab: "Analyze past test..." picks a .txt/.md file, analyzes it in the background, shows the result for
  review and only calls `recordPastTest` after I confirm (UC08). The file chooser moved into `TabPanel`.

**Fallback tuning**
- The first version of the fallback gave an item due tomorrow only one 60-minute block on its only study day, and
  the time it could not use was wasted, so the plan ended mid-week. After reviewing the output, the AI changed it so
  an item's unusable time is given to the other priorities, deadline items with few days left can take longer
  blocks, weak-topic review stays at most 60 min a day, and blocks under 30 min are skipped when more time is left.

**How it was verified**
- `mvn -q compile` with IntelliJ's bundled Maven.
- Smoke test from a scratch folder (real `data-old` untouched), 28 checks passed: empty data and invalid hours rejected;
  AI plan with bad tasks (unknown course, date outside the week, bad date) dropped, course code normalized, a day
  trimmed from 150 to 120 min with a note; prompt contains the daily limit, deadlines, weak topic, recent minutes,
  grade and recent events; ranking order; plan outdated after a session ended; retry after a bad reply; fallback
  after an LLM failure, after two bad replies and on the offline mock, each respecting the daily limit and deadlines.
- GUI driven by the harness: Planner generated a plan and showed the outdated notice after a grade was entered;
  Analyze past test went through the real file chooser and confirm dialog and added the result to mastery.
- Ran `plan` and `exit` in the CLI. Checked `git status` before committing: `.env`, `data-old` and `AI_LOG.md` were not
  staged.

**Known limitations**
- The study plan is kept in memory only (not saved to `data-old`), so it is gone after a restart.
- Adding a course or assessment does not mark the plan outdated, because `CourseManager` does not send Observer
  events; only grades, mastery, sessions and deadlines do.
- Not yet tested with the real Gemini key at the time of this entry.
- Next: README (step 7).

## 2026-11-14: End-to-end verification (Nov 14)

Before recording the demo videos, the AI ran a full end-to-end check of F01 to F12: scripted CLI sessions from a
scratch folder (real `data-old` untouched) with the real Gemini key, an invalid-key run to check every LLM fallback,
and screenshots of all nine GUI tabs. Everything passed after three fixes:
- `e3bcd52` Fix wrong-source citations in F05 explain (word-start keyword matching, at least two keywords per passage).
- `7a2118b` Fix F03 course filter for all deadline sections.
- `634c70b` Show overdue deadlines on F11 dashboard.

Full results table, bug details and notes: [docs/verification.md](docs/verification.md) (commit `fae6938`).

---

## 2026-11-09: Step 7, README

**Tool:** Claude Code (Claude Opus 5.5)
**Commits:** `838655f` Write README with setup, run instructions, features and patterns; `1b68b38` Tidy README

- Claude Code wrote the README (prerequisites, setup, `.env` from `.env.example`, GUI/CLI launch, example CLI
  session, project structure, patterns). I edited the API key section myself on GitHub (`00f29d5`, `eaa550a`),
  which caused a rejected push; Claude Code rebased onto my edits without force-pushing, then tidied the README.

---

## 2026-11-15: Manual testing, demo videos, diagram and report (Claude Cowork)

**Tool:** Claude Cowork (Claude Opus 5.5)

- I tested every feature by hand in the GUI and CLI with the real Gemini key before recording. All passed.
  Two results that looked wrong were not bugs: the notes had not been re-uploaded after I reset the data, and
  quiz topics with no matching notes fall back to general questions (retrieval is keyword-based).
- Claude wrote a demo video script and sample input files (notes, syllabus, past test). I recorded three videos
  (GUI part 1, GUI part 2, CLI).
- The Gemini free-tier quota ran out during recording; I switched `GEMINI_MODEL` in `.env` to `gemini-3.8-flash`.
- My API key was visible for a few seconds at the start of one video. I deleted the video, uploaded a trimmed
  version, and revoked the key in Google AI Studio and created a new one.
- Claude generated the updated Stage 2 class diagram for UMLet from the final code, and helped draft and review
  the Stage 2 report.

---

## 2026-11-24: CLI dashboard command (F11)

**Tool:** Claude Code (Claude Opus 5.5)

- I asked Claude Code to add a `dashboard` command to the CLI so every feature is reachable from the CLI.
  It prints the existing `ProgressDashboard.Stats`: total study minutes, minutes and current grade per course
  with a `#` bar, weakest topics, overdue/upcoming/completed deadlines and the last update time.
- While testing in a scratch folder with the offline mock, Claude Code found that `getDashboard()` returned a
  stale snapshot after adding a course or assessment (those do not notify the dashboard). The command uses
  `refreshDashboard()` instead, the same way the GUI's Dashboard tab does.
- Added `dashboard` to the help text and a CLI command table to the README.

---

## 2026-11-24: Standalone CLI jar (`java -jar Agent.jar -LLMAPI -input task.txt`)

**Tool:** Claude Code (Claude Opus 5.5)

- For the professor's requirement, Claude Code added the maven-shade-plugin so `mvn clean package` builds a runnable
  `target/Agent.jar` with Gson inside, and a `CliOptions` parser in `Main`: `--help`, `--cli`, `-input FILE`,
  `-LLMAPI`/`--llm`, `--mock` and `--model NAME`, with exit codes 0/1/2.
- `Config` got a runtime override map (checked before environment variables and `.env`), used for `--model` and to
  force the mock. `CLIApp` can read from a script file: it echoes each answer after its prompt and stops at the end
  of the file.
- Added `samples/task.txt` (builds a study plan) and a "Standalone CLI" README section. Claude Code tested the jar
  from scratch folders with the mock, the missing-key error, bad options, a missing input file and a run from
  another folder, without using my `.env` or data folders.
