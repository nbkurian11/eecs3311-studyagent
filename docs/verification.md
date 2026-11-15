# End-to-end verification (Nov 14, 2026)

Full check of StudyBetter before recording the demo videos.

**Setup**
- Built with `mvn -q compile` (Java 25 runtime, release 21).
- Ran from a scratch working directory with its own `data/`, so the real `data/studybetter.json` was not touched.
  The real Gemini key was read from a copy of `.env` and was never printed.
- The CLI (`studybetter.Main --cli`) was driven by scripted input covering F01 to F12.
- LLM failure was simulated by overriding `GEMINI_API_KEY` with an invalid key (environment variable), on a copy
  of the scratch data.
- The GUI was built off-screen by a small test harness that opened every tab and saved a screenshot. The
  Dashboard was also captured on the real screen with `java.awt.Robot`.

## Results

| Feature | What was tested | Result | Notes |
|---|---|---|---|
| F01 Courses | add-course, list, duplicate course | PASS | Duplicate (lowercase `eecs3311`) rejected: "A course with code EECS3311 already exists" |
| F01 Materials | upload `.md` notes, AI summary, `materials` | PASS | 7 accurate key points. Missing file gives "File not found" |
| F02 Syllabus import | extract, review screen, confirm, save | PASS | 5 assessments (total 100%) and 10 topics. Warned that the Final Exam has no date |
| F03 Deadlines | upcoming / due soon / overdue / no date, mark complete, course filter | PASS (after fix 2) | Completing Assignment 1 moved it out of "Due soon" |
| F04 Grades | grade, grade-status with an 80% target | PASS | 13/20 shows 65.0% and "need 85.0% on the remaining work" (checked by hand). Non-numeric score rejected |
| F05 Explain, with sources | push vs pull question; one-word "Observers" | PASS | Cites [Source n] and lists `observer-notes` |
| F05 Explain, general answer | "How does a red-black tree rotation work?" | PASS (after fix 1) | Before the fix it cited `observer-notes` by mistake. Now labelled "(General answer, not from your course materials.)" |
| F06 Quiz | weak-topics quiz and chosen-topics quiz: generate, answer, auto-grade | PASS | Multiple choice graded in code, short answers graded by the AI, feedback for each question, scores by topic |
| F07 Mock exam | simulate the Final Exam, 4 questions, 20-minute limit | PASS | Topic split, countdown timer, auto-grading |
| F08 Past test | analyze a graded midterm, confirm, update mastery | PASS | Explains each lost mark and gives scores by topic |
| F08 Grade practice | `grade-practice`, `practices` | PASS | Lists every attempt; "Nothing to grade" once all are graded |
| F09 Mastery | before any data, after the past test, after quizzes | PASS | Weakest first; Weak / Developing / Strong labels update |
| F10 Sessions | start, end | PASS | Session logged with its minutes |
| F11 Dashboard | GUI tab after grades, mastery and deadline changes | PASS (after fix 3) | Study minutes, grades, weakest topics, deadlines. Overdue items now shown in red |
| F12 Study plan | AI plan, then a new grade, then plan again | PASS | Says "Your last plan is outdated (your grade data changed...)" and builds a new one with updated priorities |
| Error cases | bad date `2026-13-45`, duplicate course, unknown command, non-numeric score, missing file | PASS | Clear messages, no crashes |
| LLM failure (invalid key) | upload, summarize, explain (x2), syllabus, quiz, mock exam, past test, plan | PASS | Material saved with summary pending; explain shows the matching passages instead; syllabus falls back to manual entry; quiz, mock exam and past test show clear errors; plan uses the deadline x weight fallback with a note |
| GUI (all 9 tabs) | Courses, Deadlines, Grades, Learn, Practice, Mastery, Sessions, Planner, Dashboard | PASS | Every tab shows the live data |

## Bugs found and fixed

| Commit | Bug | Fix |
|---|---|---|
| `e3bcd52` Fix wrong-source citations in F05 explain | `MaterialSearchTool` matched keywords as raw substrings ("red" matched "CreditCardStrategy"), and one common word such as "work" was enough to count a passage as relevant, so a general question was answered as if it came from the course materials and cited them as sources. The "AI request failed" message also had a doubled period. | Keywords only match at the start of a word, and a passage needs at least two different keywords (or the only keyword, for a one-keyword question). Trailing period removed before the message is built. |
| `7a2118b` Fix F03 course filter for all deadline sections | `getDeadlines(courseCode)` filtered only "Upcoming"; "Due soon", "Overdue" and "No due date" always showed every course. Undated items printed as "due No date". | Filtered versions of `getDueSoon`, `getOverdue` and `getUndated` in `DeadlineTracker`, used by `AgentController`. Wording changed to "no due date". |
| `634c70b` Show overdue deadlines on F11 dashboard | The Dashboard's deadlines box showed only upcoming and completed items, so overdue work disappeared from it. | `ProgressDashboard.Stats` has an overdue list; `DashboardPanel` shows it in red above Upcoming. |

Each fix was compiled and the affected cases were run again.

## Worth knowing

- **Mock exam topic coverage.** When the syllabus gives no topic weights and few questions are requested, only the
  first topics (weeks 1 to 4 for a 4-question exam) get questions, and the warning calls the others "low-weight"
  even though all weights are equal. Asking for 10 or more questions covers every topic.
- **Characters in redirected output.** Gemini's em-dashes and curly quotes can show as `?` when CLI output is
  redirected to a file on Windows (console code page). IntelliJ's console shows them correctly.
- **Off-screen screenshots.** In screenshots rendered off-screen, the Dashboard header can look cut off
  ("studied in tota"). On a real screen it shows in full, so this is not an app bug.
- **The study plan is in memory only.** The "outdated" notice works within one run; the plan is gone after a
  restart.
