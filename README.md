# StudyBetter

StudyBetter is an AI study agent for university students, built for EECS 3311 (York University, Fall 2026).
It organizes your courses, deadlines and grades, helps you learn from your own course materials
(explanations, quizzes, mock exams, grading with feedback), tracks how well you know each concept, and builds
a weekly study plan. It has a Swing GUI and a command-line interface. The AI runs on Google Gemini, and the
app still works offline (without the AI features) when no API key is set.

## Features

| ID | Feature | What it does |
|---|---|---|
| F01 | Courses and materials | Add/delete courses; upload .txt/.md notes and get an AI summary (marked pending if the AI is unavailable). |
| F02 | Syllabus import | The AI extracts assessments (title, due date, weight) and topics from a syllabus; you review them before saving. |
| F03 | Deadlines | Overdue, due-soon, upcoming and undated assessments, with a course filter and "mark complete". |
| F04 | Grades | Enter marks, see your current weighted grade and the score you need on the remaining work for a target. |
| F05 | Explain a concept | Answers questions using passages retrieved from your materials, with sources; otherwise a labelled general answer. |
| F06 | Practice quiz | Generates a quiz on chosen topics or your weak topics (count and difficulty). |
| F07 | Mock exam | Timed exam with questions spread across syllabus topics by their weights. |
| F08 | Grading and past tests | Multiple choice graded automatically, short answers by the AI with feedback; analyzes a graded past test. |
| F09 | Concept mastery | Tracks a mastery level (Weak / Developing / Strong) per topic from every graded result. |
| F10 | Study sessions | Start/end study sessions and see total study time per course. |
| F11 | Progress dashboard | Study minutes, current grades, weakest topics and deadlines, with bar charts; refreshes automatically. |
| F12 | Study plan | Multi-step agent: deadlines, grades, weak topics and recent study, then a one-week schedule within your hours per day. |

## Prerequisites

- JDK 21 or newer
- Maven 3.9+, **or** IntelliJ IDEA (which includes Maven)
- A free Google Gemini API key (optional, but needed for the AI features)

## Setup

```bash
git clone https://github.com/nbkurian11/eecs3311-studyagent.git
cd eecs3311-studyagent
cp .env.example .env        # Windows: copy .env.example .env
```

1. Get a free API key at <https://aistudio.google.com> ("Get API key").
2. Open `.env` and set your key:
   ```
   GEMINI_API_KEY=your-key-here
   GEMINI_MODEL=gemini-3.8-flash
   GEMINI_THINKING_LEVEL=low
   ```
   If the default model is overloaded or slow (timeouts), set `GEMINI_MODEL=gemini-3.5-flash`.

Without a key, StudyBetter starts on an offline mock: everything except the AI features works, and the AI
features say they are unavailable (the study plan falls back to a simple deadline × weight plan).

Your data is saved to `data/studybetter.json`. Both `data/` and `.env` are in `.gitignore`.

## Running

**IntelliJ IDEA:** open the folder as a Maven project and run `studybetter.Main`.
- GUI: no program arguments (the default).
- CLI: add `--cli` under *Run > Edit Configurations > Program arguments*.

**Maven** (from the project folder):

```bash
mvn compile exec:java                       # GUI
mvn compile exec:java -Dexec.args="--cli"   # CLI
```

The GUI has one tab per area: Courses, Deadlines, Grades, Learn, Practice, Mastery, Sessions, Planner and
Dashboard. Slow AI calls run in the background, with "Working..." shown in the status bar.

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

> deadlines
Course code (blank for all):
Upcoming:
  EECS3311 | Project Stage 2 | due 2026-11-10 | 20.0%
Due soon (next 7 days):
  EECS3311 | Project Stage 2 | due 2026-11-10 | 20.0%
...

> plan
Hours available per day [2]: 1.5
Planning (checking deadlines, weak topics, grades and recent sessions)...

Priorities (most urgent first):
  1. EECS3311: Project Stage 2 (due tomorrow, worth 20%; only 0 min studied this week)  [score 77.0]

Study plan 2026-11-09 to 2026-11-15 (max 90 min/day):

2026-11-09 Mon (90 min)
   90 min  EECS3311: Prepare for Project Stage 2
           why: due tomorrow, worth 20%; only 0 min studied this week
...
  ! Simple plan sorted by deadline x weight, because the AI was not available (no GEMINI_API_KEY in .env, running on the offline mock).

> exit
Goodbye.
```

With a key, the same commands give AI-built plans, and `upload-material`, `import-syllabus`, `explain`, `quiz`,
`mock-exam`, `grade-practice` and `analyze-past-test` become available.

## Project structure

```
src/main/java/studybetter/
  Main, Config      entry point (GUI by default, --cli for the CLI); reads settings from .env
  domain/           Course, Material, AssessmentItem, Topic, StudySession, GradeResult, ConceptMastery,
                    Question, PracticeSettings, PracticeAssessment, Quiz, MockExam, SyllabusDraft,
                    Explanation, StudyPlan, StudyTask, StudyPriority
  persistence/      DataStore (saves everything to data/studybetter.json) and Gson type adapters
  llm/              LLMClient interface, GeminiClient (Gemini REST API), MockLLMClient, LLMException
  observer/         Subject, DataObserver
  service/          CourseManager, DeadlineTracker, GradeBook, MasteryTracker, SessionLogger,
                    PracticeManager, ProgressDashboard
  agent/            StudyAgent, ToolRegistry, Tool, MaterialSearchTool, DeadlineTool, ProgressTool,
                    PlanScheduler, AssessmentFactory
  controller/       AgentController (the only class the UIs talk to)
  ui/               CLIApp; MainWindow and one panel per tab (Swing), BarChart
```

## Design patterns

| Pattern | Classes | Problem it solves |
|---|---|---|
| Singleton | `DataStore` | One shared, consistent copy of the saved data for every service. |
| Observer | `Subject`, `DataObserver`; `GradeBook`, `MasteryTracker`, `SessionLogger`, `DeadlineTracker` notify; `ProgressDashboard`, `StudyAgent` and the Dashboard/Planner tabs observe | The dashboard refreshes and the study plan is marked outdated when data changes, without the services knowing about them. |
| Strategy | `LLMClient` with `GeminiClient` / `MockLLMClient` | The agent works with Gemini or offline without code changes. |
| Adapter | `GeminiClient` | Turns Gemini's REST/JSON API into a simple `generate(String)` call. |
| Facade | `AgentController` | The CLI and GUI use one simple entry point instead of every service. |
| Template Method | `PracticeAssessment.build()` with `Quiz` and `MockExam` | Same build steps (settings, topics, questions) in a fixed order; subclasses change how topics and questions are chosen. |
| Simple Factory | `AssessmentFactory.create(type, settings)` | Creates a `Quiz` or `MockExam` from the type the user picks. |

## API Key Setup

StudyBetter uses Google's Gemini API for its AI features (summaries, explanations,
syllabus import, quizzes, grading feedback and study plans). The free tier is enough
to run the app; no credit card is needed.

1. **Get a key:** go to [Google AI Studio](https://aistudio.google.com), sign in,
   click **Get API key**, then **Create API key**, and copy it.

2. **Create your local config file** in the project root by copying the example:

   ```bash
   # Windows
   copy .env.example .env
   # macOS / Linux
   cp .env.example .env
   ```

3. **Add your key** by opening `.env` and replacing the placeholder:

GEMINI_API_KEY=your_key_here
GEMINI_MODEL=gemini-3.5-flash


   If the model is busy (HTTP 503 or timeouts), try another Flash model listed in AI Studio.

4. **Run the app.** It reads `.env` at startup, so restart it after any change.

### Keeping your key safe
- `.env` is listed in `.gitignore`, so it is never committed. Only `.env.example`
  (placeholders, no real key) is in the repository.
- Never put a real key in source code, screenshots, videos or commit messages.
- If a key is ever pushed by accident, revoke it in AI Studio right away and create a new one.

### Running without a key
If no key is set, the app still starts and uses an offline mock: deterministic features
(courses, deadlines, grades, sessions, mastery, dashboard) work normally, and AI
features report that the AI service is unavailable.


