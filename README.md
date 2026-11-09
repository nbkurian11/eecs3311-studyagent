# StudyBetter

StudyBetter is an AI study agent for university students, built for EECS 3311 (York University, Fall 2026).
It organizes your courses, deadlines and grades, helps you learn from your own course materials (explanations,
quizzes, mock exams, grading with feedback), tracks how well you know each concept, and builds a weekly study plan.

It has a **Swing GUI** and a **command-line interface (CLI)**. The AI runs on **Google Gemini**. Without an API key
the app still works, but the AI features are unavailable.

## Features

| ID | Feature | What it does |
|---|---|---|
| F01 | Courses and materials | Add and delete courses. Upload `.txt`/`.md` notes and get an AI summary (marked pending if the AI is unavailable). |
| F02 | Syllabus import | The AI extracts assessments (title, due date, weight) and topics from a syllabus. You review them before saving. |
| F03 | Deadlines | Overdue, due-soon, upcoming and undated assessments, with a course filter and "mark complete". |
| F04 | Grades | Enter marks. See your current weighted grade and the score you need on the remaining work for a target. |
| F05 | Explain a concept | Answers questions from passages retrieved from your materials, with sources. Otherwise gives a labelled general answer. |
| F06 | Practice quiz | Generates a quiz on chosen topics or your weak topics, with a chosen number of questions and difficulty. |
| F07 | Mock exam | Timed exam with questions spread across syllabus topics by their weights. |
| F08 | Grading and past tests | Multiple choice is graded automatically and short answers by the AI, with feedback. Also analyzes a graded past test. |
| F09 | Concept mastery | Tracks a mastery level per topic (Weak / Developing / Strong) from every graded result. |
| F10 | Study sessions | Start and end study sessions and see total study time per course. |
| F11 | Progress dashboard | Study minutes, current grades, weakest topics and deadlines, with bar charts. Refreshes automatically. |
| F12 | Study plan | Multi-step agent: uses deadlines, grades, weak topics and recent study to build a one-week schedule within your hours per day. |

## Prerequisites

- **JDK 21 or newer**
- **Maven 3.9+**, or **IntelliJ IDEA** (which includes Maven)
- A free **Google Gemini API key** (optional, but needed for the AI features)

## Setup

1. **Clone the repository**

   ```bash
   git clone https://github.com/nbkurian11/eecs3311-studyagent.git
   cd eecs3311-studyagent
   ```

2. **Create your local config file** from the example (it contains placeholders only):

   ```bash
   # Windows
   copy .env.example .env
   # macOS / Linux
   cp .env.example .env
   ```

3. **Get a free Gemini API key** at [Google AI Studio](https://aistudio.google.com): sign in, click
   **Get API key**, then **Create API key**, and copy it. No credit card is needed for the free tier.

4. **Add the key to `.env`**:

   ```
   GEMINI_API_KEY=your_key_here
   GEMINI_MODEL=gemini-3.5-flash
   GEMINI_THINKING_LEVEL=low
   ```

   If a model is busy (HTTP 503 or timeouts), set `GEMINI_MODEL` to another Flash model listed in AI Studio.
   The app reads `.env` at startup, so restart it after any change.

### Keeping your key safe

- `.env` is listed in `.gitignore` and is never committed. Only `.env.example`, which has placeholders and no real
  key, is in the repository.
- Never put a real key in source code, screenshots, videos or commit messages.
- If a key is ever pushed by accident, revoke it in AI Studio right away and create a new one.

### Running without a key

If no key is set, the app starts on an offline mock. Courses, deadlines, grades, sessions, mastery and the dashboard
work normally. The AI features report that the AI service is unavailable, and the study plan falls back to a simple
deadline × weight plan.

Your data is saved to `data/studybetter.json`, which is also in `.gitignore`.

## Running

**IntelliJ IDEA:** open the folder as a Maven project and run `studybetter.Main`.

- **GUI:** no program arguments (the default)
- **CLI:** add `--cli` under *Run → Edit Configurations → Program arguments*

**Maven** (from the project folder):

```bash
mvn compile exec:java                       # GUI
mvn compile exec:java -Dexec.args="--cli"   # CLI
```

The GUI has one tab per area: **Courses, Deadlines, Grades, Learn, Practice, Mastery, Sessions, Planner** and
**Dashboard**. Slow AI calls run in the background, with "Working..." shown in the status bar.

## Example CLI session

Type `help` for all commands, and `quit` or `exit` to leave. This session ran on the offline mock.

```
> add-course
Course code: EECS3311
Course name: Software Design
Term (e.g. F26): F26
Added EECS3311 - Software Design (F26)

> add-item
Course code: EECS3311
Title: Project Stage 2
Due date (YYYY-MM-DD, blank for none): 2026-11-10
Weight (%): 20
Added Project Stage 2 to EECS3311

> grade
Course code: EECS3311
  1. Midterm | due 2026-11-02 | 25.0%
  2. Project Stage 2 | due 2026-11-10 | 20.0%
Pick a number: 1
Score: 21
Out of: 25
Saved 21.0/25.0 for 'Midterm'.

> plan
Hours available per day [2]: 1.5
Planning (checking deadlines, weak topics, grades and recent sessions)...

Priorities (most urgent first):
  1. EECS3311: Project Stage 2 (due tomorrow, worth 20%; only 0 min studied this week)

2026-11-09 Mon (90 min)
   90 min  EECS3311: Prepare for Project Stage 2
           why: due tomorrow, worth 20%; only 0 min studied this week
  ! Simple plan sorted by deadline x weight, because the AI was not available (offline mock).

> exit
Goodbye.
```

(The Midterm was added earlier with `add-item`.) With a key, the same session gives AI-built plans, and these
commands become available: `upload-material`, `import-syllabus`, `explain`, `quiz`, `mock-exam`, `grade-practice`
and `analyze-past-test`.

## Project structure

```
src/main/java/studybetter/
  Main, Config      entry point (GUI by default, --cli for the CLI); reads settings from .env
  domain/           Course, Material, AssessmentItem, Topic, StudySession, GradeResult, ConceptMastery,
                    Question, QuestionGenerator, PracticeSettings, PracticeAssessment, Quiz, MockExam,
                    SyllabusDraft, Explanation, StudyPlan, StudyTask, StudyPriority
  persistence/      DataStore (saves everything to data/studybetter.json) and Gson type adapters
  llm/              LLMClient interface, GeminiClient (Gemini REST API), MockLLMClient, LLMException
  observer/         Subject, DataObserver
  service/          CourseManager, DeadlineTracker, GradeBook, MasteryTracker, SessionLogger,
                    PracticeManager, ProgressDashboard
  agent/            StudyAgent, ToolRegistry, Tool, MaterialSearchTool, DeadlineTool, ProgressTool,
                    PlanScheduler, AssessmentFactory
  controller/       AgentController (the only class the UIs talk to)
  ui/               CLIApp; MainWindow and one panel per tab (Swing); BarChart
```

## Design patterns

| Pattern | Classes | Problem it solves |
|---|---|---|
| Singleton | `DataStore` | Every service shares one consistent copy of the saved data. |
| Observer | `Subject`, `DataObserver`. `GradeBook`, `MasteryTracker`, `SessionLogger` and `DeadlineTracker` notify; `ProgressDashboard`, `StudyAgent` and the Dashboard/Planner tabs observe. | The dashboard refreshes and the study plan is marked outdated when data changes, without the services knowing who is listening. |
| Strategy | `LLMClient` with `GeminiClient` / `MockLLMClient` | The agent works with Gemini or offline without code changes. |
| Adapter | `GeminiClient` | Turns Gemini's REST/JSON API into a simple `generate(String)` call. |
| Facade | `AgentController` | The CLI and GUI use one simple entry point instead of every service. |
| Template Method | `PracticeAssessment.build()` with `Quiz` and `MockExam` | The same build steps (settings, topics, questions) run in a fixed order; subclasses change how topics and questions are chosen. |
| Simple Factory | `AssessmentFactory.create(type, settings)` | Creates a `Quiz` or `MockExam` from the type the user picks. |

## AI use

AI development tools were used to build this project, as required by the course: Claude (Anthropic), through Claude
Code and the Claude desktop app. All generated code was reviewed, run and tested before it was committed. Details are
in the Stage 2 report.
