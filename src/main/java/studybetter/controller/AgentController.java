package studybetter.controller;

import studybetter.Config;
import studybetter.agent.DeadlineTool;
import studybetter.agent.MaterialSearchTool;
import studybetter.agent.StudyAgent;
import studybetter.agent.ToolRegistry;
import studybetter.domain.AssessmentItem;
import studybetter.domain.ConceptMastery;
import studybetter.domain.Course;
import studybetter.domain.Explanation;
import studybetter.domain.Material;
import studybetter.domain.StudySession;
import studybetter.domain.SyllabusDraft;
import studybetter.domain.Topic;
import studybetter.llm.GeminiClient;
import studybetter.llm.LLMClient;
import studybetter.llm.LLMException;
import studybetter.llm.MockLLMClient;
import studybetter.observer.DataObserver;
import studybetter.service.CourseManager;
import studybetter.service.DeadlineTracker;
import studybetter.service.DeadlineTracker.Deadline;
import studybetter.service.GradeBook;
import studybetter.service.MasteryTracker;
import studybetter.service.SessionLogger;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Facade pattern: the single entry point the CLI and GUI use. It hides the services
 * (CourseManager, DeadlineTracker, GradeBook, MasteryTracker, SessionLogger) and the {@link StudyAgent},
 * and turns LLM failures into graceful results instead of crashes.
 */
public class AgentController {

    public record DeadlineReport(List<Deadline> upcoming, List<Deadline> dueSoon,
                                 List<Deadline> overdue, List<Deadline> undated) {}

    /** Result of summarizing a material (on upload or retry). {@code summaryError} is null when it succeeded. */
    public record UploadResult(Material material, String summaryError) {
        public boolean summarized() { return summaryError == null; }
    }

    /** Result of extracting a syllabus. {@code draft} is null and {@code error} explains why when it failed. */
    public record SyllabusResult(SyllabusDraft draft, String error) {
        public boolean extracted() { return draft != null; }
    }

    /** What {@link #saveSyllabus} actually saved; {@code skipped} lists titles already in the course. */
    public record SyllabusSaveResult(int itemsAdded, int topicsAdded, List<String> skipped) {}

    private final CourseManager courseManager;
    private final DeadlineTracker deadlineTracker;
    private final GradeBook gradeBook;
    private final MasteryTracker masteryTracker;
    private final SessionLogger sessionLogger;
    private final StudyAgent agent;
    private final boolean usingRealLlm;

    /** Uses Gemini when GEMINI_API_KEY is set, otherwise the offline mock. */
    public AgentController() {
        this(Config.hasApiKey() ? new GeminiClient() : new MockLLMClient(), Config.hasApiKey());
    }

    /** Lets tests and the UI choose the LLM (Strategy). */
    public AgentController(LLMClient llm, boolean usingRealLlm) {
        this(new CourseManager(), new DeadlineTracker(), new GradeBook(),
                new MasteryTracker(), new SessionLogger(), llm, usingRealLlm);
    }

    public AgentController(CourseManager courseManager, DeadlineTracker deadlineTracker,
                           GradeBook gradeBook, MasteryTracker masteryTracker, SessionLogger sessionLogger,
                           LLMClient llm, boolean usingRealLlm) {
        this.courseManager = courseManager;
        this.deadlineTracker = deadlineTracker;
        this.gradeBook = gradeBook;
        this.masteryTracker = masteryTracker;
        this.sessionLogger = sessionLogger;
        this.usingRealLlm = usingRealLlm;

        ToolRegistry tools = new ToolRegistry();
        tools.register(new MaterialSearchTool(courseManager));
        tools.register(new DeadlineTool(deadlineTracker));
        this.agent = new StudyAgent(llm, tools);
        addObserver(agent);
    }

    /** False when running on the offline mock (no API key), so the UI can warn the user. */
    public boolean isUsingRealLlm() {
        return usingRealLlm;
    }

    public Course addCourse(String code, String name, String term) {
        return courseManager.addCourse(code, name, term);
    }

    public List<Course> getCourses() {
        return courseManager.getAllCourses();
    }

    public Course findCourse(String code) {
        return courseManager.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("No course with code " + code.toUpperCase()));
    }

    public void deleteCourse(String code) {
        courseManager.deleteCourse(findCourse(code).getId());
    }

    /**
     * F01: reads a .txt/.md file, asks the agent for a summary and saves the material to the course.
     * If the LLM is unavailable (or only the offline mock is configured), the material is still saved
     * and its summary is marked pending.
     */
    public UploadResult uploadMaterial(String courseCode, String filePath) {
        Course course = findCourse(courseCode);
        if (filePath == null || filePath.isBlank()) throw new IllegalArgumentException("File path is required");
        Material material = courseManager.readMaterialFile(Path.of(filePath.trim()));
        String error = null;
        if (!usingRealLlm) {
            // the offline mock only returns placeholder text, so leave the summary for 'summarize' later
            material.markSummaryPending();
            error = "no GEMINI_API_KEY in .env, running on the offline mock";
        } else {
            try {
                material.setSummary(agent.summarize(material));
            } catch (LLMException e) {
                material.markSummaryPending();
                error = e.getMessage();
            }
        }
        courseManager.addMaterial(course.getId(), material);
        return new UploadResult(material, error);
    }

    public List<Material> getMaterials(String courseCode) {
        return findCourse(courseCode).getMaterials();
    }

    /**
     * Retries the AI summary for every material whose summary is pending.
     * Returns one outcome per pending material; failed ones stay pending and carry the error reason.
     */
    public List<UploadResult> retryPendingSummaries(String courseCode) {
        List<UploadResult> outcomes = new ArrayList<>();
        for (Material m : getMaterials(courseCode)) {
            if (!m.isSummaryPending()) continue;
            if (!usingRealLlm) {
                // the mock would only store placeholder text
                outcomes.add(new UploadResult(m, "no GEMINI_API_KEY in .env, running on the offline mock"));
                continue;
            }
            try {
                courseManager.updateSummary(m, agent.summarize(m));
                outcomes.add(new UploadResult(m, null));
            } catch (LLMException e) {
                outcomes.add(new UploadResult(m, e.getMessage()));
            }
        }
        return outcomes;
    }

    /** F05: explains a concept using the course's materials when they match, otherwise a labelled general answer. */
    public Explanation explainConcept(String courseCode, String question) {
        return agent.explain(findCourse(courseCode), question);
    }

    /**
     * F02 step 1: reads a .txt/.md syllabus and asks the agent to extract assessments and topics.
     * Nothing is saved here; the UI shows the draft and calls {@link #saveSyllabus} after the student confirms.
     * If the LLM is unavailable the result carries the error, so the UI can offer manual entry.
     */
    public SyllabusResult importSyllabus(String courseCode, String filePath) {
        Course course = findCourse(courseCode);
        if (filePath == null || filePath.isBlank()) throw new IllegalArgumentException("File path is required");
        String text = courseManager.readTextFile(Path.of(filePath.trim()));
        if (!usingRealLlm) {
            // the offline mock cannot read a syllabus
            return new SyllabusResult(null, "no GEMINI_API_KEY in .env, running on the offline mock");
        }
        SyllabusDraft draft;
        try {
            draft = agent.extractSyllabus(text);
        } catch (LLMException e) {
            return new SyllabusResult(null, e.getMessage());
        }
        for (AssessmentItem item : draft.getItems()) {
            if (hasItemTitled(course, item.getTitle())) {
                draft.addWarning("'" + item.getTitle() + "' is already in " + course.getCode()
                        + " and will be skipped.");
            }
        }
        return new SyllabusResult(draft, null);
    }

    /** F02 step 2: saves a confirmed draft, skipping assessments and topics the course already has. */
    public SyllabusSaveResult saveSyllabus(String courseCode, SyllabusDraft draft) {
        Course course = findCourse(courseCode);
        if (draft == null || draft.isEmpty()) throw new IllegalArgumentException("Nothing to import");
        List<AssessmentItem> newItems = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (AssessmentItem item : draft.getItems()) {
            if (hasItemTitled(course, item.getTitle())) skipped.add(item.getTitle());
            else newItems.add(item);
        }
        List<Topic> newTopics = draft.getTopics().stream()
                .filter(t -> course.getTopics().stream().noneMatch(old -> old.getName().equalsIgnoreCase(t.getName())))
                .toList();
        courseManager.importSyllabus(course.getId(), newItems, newTopics);
        return new SyllabusSaveResult(newItems.size(), newTopics.size(), skipped);
    }

    public List<Topic> getTopics(String courseCode) {
        return findCourse(courseCode).getTopics();
    }

    private static boolean hasItemTitled(Course course, String title) {
        return course.getItems().stream().anyMatch(i -> i.getTitle().equalsIgnoreCase(title));
    }

    public AssessmentItem addAssessment(String courseCode, String title, LocalDate dueDate, double weight) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("Title is required");
        if (weight < 0 || weight > 100) throw new IllegalArgumentException("Weight must be between 0 and 100");
        AssessmentItem item = new AssessmentItem(title, dueDate, weight);
        courseManager.addAssessment(findCourse(courseCode).getId(), item);
        return item;
    }

    public DeadlineReport getDeadlines(String courseCodeFilter) {
        return new DeadlineReport(
                deadlineTracker.getUpcoming(courseCodeFilter),
                deadlineTracker.getDueSoon(),
                deadlineTracker.getOverdue(),
                deadlineTracker.getUndated());
    }

    public void markComplete(String itemId) {
        deadlineTracker.markComplete(itemId);
    }

    public void enterGrade(String itemId, double score, double outOf) {
        gradeBook.enterGrade(itemId, score, outOf);
    }

    public OptionalDouble currentGrade(String courseCode) {
        return gradeBook.currentGrade(findCourse(courseCode).getId());
    }

    public GradeBook.RequiredScore requiredScore(String courseCode, double targetPercent) {
        return gradeBook.requiredScore(findCourse(courseCode).getId(), targetPercent);
    }

    public List<ConceptMastery> getMastery(String courseCode) {
        return masteryTracker.getMastery(findCourse(courseCode).getId());
    }

    public StudySession startSession(String courseCode, String topic) {
        return sessionLogger.start(findCourse(courseCode).getId(), topic);
    }

    public StudySession endSession() {
        return sessionLogger.end();
    }

    public Optional<StudySession> getActiveSession() {
        return sessionLogger.getActiveSession();
    }

    public boolean sessionNeedsConfirmation(StudySession s) {
        return sessionLogger.needsConfirmation(s);
    }

    public int totalStudyMinutes(String courseCode) {
        return sessionLogger.totalMinutes(findCourse(courseCode).getId());
    }

    public void addObserver(DataObserver observer) {
        gradeBook.attach(observer);
        masteryTracker.attach(observer);
        sessionLogger.attach(observer);
    }
}