# StudyBetter: Project Context for Claude Code

EECS 3311 (York University, Fall 2026) course project, Stage 2 (implementation).
Owner: Noel Kurian. Repo: github.com/nbkurian11/eecs3311-studyagent
**Stage 2 due: November 10, 2026.** The Stage 1 design report is `stage1-3311final.pdf` in this folder.
Read it for the full feature specs (F01 to F12), use cases (UC01 to UC12) and sequence diagrams (SD01 to SD06).

## What the app is
StudyBetter is an AI study agent for students. It organizes courses, deadlines and grades, helps students learn
(explanations, quizzes, mock exams, feedback), tracks concept mastery, and builds study plans.
LLM: Google Gemini over the REST API (`GeminiClient`), with `MockLLMClient` as an offline fallback.

## Hard requirements (Stage 2 instructions)
- Must have a **GUI** (Swing) **and** a **CLI**; both reach the major features through `AgentController`.
- At least **10 meaningful features** working for real. Mockups, empty methods and hard-coded AI answers do not count.
- At least **5 design patterns visible in code**, each solving a real problem.
- **Meaningful agent behaviour**: tool use, retrieval, memory and multi-step planning, not just prompt-forwarding.
- **Never commit `.env` or API keys.** Keys are read via `Config.get("GEMINI_API_KEY", ...)` from `.env` (gitignored).
  `.env.example` holds placeholders only.
- Every commit must be a **meaningful code change** (not formatting or report-only). One feature per commit,
  with a descriptive multi-line message.
- The student must understand all the code: keep it readable and add a short class-level Javadoc
  naming each class's role and pattern.

## Tech
- Java 21 (runs on JDK 25), Maven (`pom.xml`), Gson 2.11.0. No other dependencies unless really needed
  (JFreeChart is acceptable for the dashboard chart; otherwise draw simple bars with Swing).
- Build: `mvn -q compile`. Run CLI: run `studybetter.Main` (it currently launches the CLI).
  The app should end up with `--cli` for the CLI and the GUI as the default.
- Data is saved to `data/studybetter.json` (gitignored) through the `DataStore` singleton.
- Windows machine, IntelliJ IDEA. The student commits and pushes from IntelliJ or the terminal.

## Package layout (keep it)
```
studybetter/
  Config, Main
  domain/        Course, Material, AssessmentItem, Topic, StudySession, GradeResult(+QuestionResult),
                 ConceptMastery    (to add: Question, PracticeAssessment, Quiz, MockExam, StudyPlan,
                 StudyTask, Explanation, PracticeSettings)
  persistence/   DataStore (Singleton; AppData holds courses, sessions, mastery), LocalDateAdapter,
                 LocalDateTimeAdapter
  llm/           LLMClient (Strategy interface; default generateWithContext), GeminiClient (Adapter over
                 the Gemini REST API), MockLLMClient, LLMException
  observer/      DataObserver, Subject
  service/       CourseManager, DeadlineTracker, GradeBook (extends Subject), MasteryTracker (extends Subject),
                 SessionLogger (extends Subject)   (to add: ProgressDashboard implements DataObserver)
  controller/    AgentController (Facade over all services + agent)
  agent/         (to add) StudyAgent, ToolRegistry, Tool, MaterialSearchTool, DeadlineTool, AssessmentFactory
  ui/            CLIApp (exists locally, NOT committed yet), (to add) MainWindow (Swing)
```

## Design patterns (must be real in code)
| Pattern | Classes |
|---|---|
| Singleton | `DataStore` |
| Observer | `Subject`, `DataObserver`; GradeBook/MasteryTracker/SessionLogger notify; ProgressDashboard and StudyAgent observe |
| Strategy | `LLMClient` with GeminiClient / MockLLMClient; StudyAgent depends only on the interface |
| Adapter | `GeminiClient` adapts the Gemini REST JSON API to `LLMClient.generate(String)` |
| Facade | `AgentController` (UI talks only to it) |
| Template Method | `PracticeAssessment.build()` calls applySettings() then abstract selectTopics() then abstract generateQuestions(); Quiz and MockExam override |
| Simple Factory | `AssessmentFactory.create(type, settings)` returns Quiz or MockExam. **Label it "Simple Factory", not "Factory Method"** (Stage 1 lost a mark for that label) |

## Current status (as of Nov 9)
Done and pushed: project setup/Config, domain basics, DataStore, LLM layer, CourseManager (F01 CRUD),
DeadlineTracker (F03), GradeBook (F04), SessionLogger (F10), MasteryTracker (F09), AgentController (Facade).
`ui/CLIApp.java` and the new `Main.java` exist locally but are **not committed**. Commit them first.

## Remaining work: do in this order, one commit each, `mvn -q compile` + manual CLI test before each commit
1. **CLI**: commit the existing `CLIApp` + `Main`. Check every command works.
2. **Agent + F05 + F01 summaries**: `agent` package with `Tool` interface (`getName()`, `execute(String)`),
   `ToolRegistry` (register/get/listTools), `MaterialSearchTool` (keyword search over course materials' text
   and summaries; returns top passages), `DeadlineTool` (upcoming deadlines and weights from DeadlineTracker).
   `StudyAgent` (holds `LLMClient` + `ToolRegistry`, implements `DataObserver`):
   - `explain(courseId, question)` returns `Explanation` (text + sources). Uses MaterialSearchTool, then
     `llm.generateWithContext`. If there are no passages, give a general answer labelled
     "not from your course materials".
   - `summarize(Material)` returns key points. Material needs a text field (read from .txt/.md; PDF text
     extraction is optional, e.g. Apache PDFBox if needed).
   Add an `uploadMaterial(courseCode, filePath)` flow (F01) and an `explainConcept` (F05) to AgentController and CLI.
   If the LLM is unavailable, the material is saved and the summary is marked pending.
3. **F02 Syllabus import**: `StudyAgent.extractSyllabus(text)` asks Gemini for **JSON**
   (assessments: title, dueDate YYYY-MM-DD or null, weight; topics: name, week), parses it with Gson, and
   validates it (warn if weights do not total 100). The CLI shows the items for review and confirms before saving.
   Fallback: manual entry.
4. **F06/F07 Practice**: `PracticeSettings`, `Question` (text, options, answer, topic, difficulty),
   `PracticeAssessment` (Template Method), `Quiz` (chosen or weak topics, N questions), `MockExam` (questions
   distributed by syllabus topic weights, time limit), `AssessmentFactory`. Questions come from the LLM as JSON;
   validate them and retry once on a bad format.
5. **F08 Grading + F12 Study plan**:
   - `submitAnswers`: multiple choice graded deterministically, short answers graded by the LLM, giving a
     `GradeResult` and then `MasteryTracker.update(result)`. `analyzePastTest` for pasted test text.
   - `StudyAgent.buildStudyPlan(hoursPerDay)`: multi-step agent. Call DeadlineTool, get weak concepts,
     grades and recent sessions, rank priorities, then ask the LLM for a JSON schedule, giving `StudyPlan` of
     `StudyTask(date, course, description, minutes, reason)`. Fallback: deterministic plan sorted by
     deadline × weight.
6. **GUI + F11 Dashboard**: Swing `MainWindow` with tabs: Courses, Deadlines, Grades, Learn (explain),
   Practice (quiz/mock exam/grading), Mastery, Sessions, Planner, Dashboard. `ProgressDashboard` implements
   `DataObserver` and refreshes stats when notified (study minutes per course, current grades, mastery,
   upcoming/completed deadlines). Run long LLM calls with `SwingWorker` so the UI does not freeze.
7. **README**: prerequisites (JDK 21+, Maven), `cp .env.example .env`, add the key, the GUI/CLI run commands,
   example CLI session.

## Conventions
- Validate input and throw `IllegalArgumentException` / `IllegalStateException` with clear messages;
  the CLI and GUI catch them and show them.
- Catch `LLMException` at the agent/controller level and degrade gracefully (the "LLM unavailable" cases in Stage 1).
- Ask the LLM for strict JSON when structure is needed; strip ```json fences before parsing.
- Keep method names consistent with the Stage 1 report where possible (addCourse, uploadMaterial,
  importSyllabus, getDeadlines, enterGrade, explainConcept, createPractice, submitAnswers, analyzePastTest,
  startSession, endSession, getDashboard, generateStudyPlan).
- Do not rewrite or reformat working classes without a reason.
- Before committing: show `git status` and confirm `.env` and `data/` are not staged.
