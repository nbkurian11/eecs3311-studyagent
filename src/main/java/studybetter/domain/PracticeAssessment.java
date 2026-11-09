package studybetter.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A generated practice test (F06 quiz or F07 mock exam) together with the student's answers.
 * <p>
 * Template Method pattern: {@link #build} runs the same steps in a fixed order for every kind of practice:
 * {@link #applySettings} (shared), then {@link #selectTopics} and {@link #generateQuestions} (abstract,
 * implemented differently by {@link Quiz} and {@link MockExam}). Callers get one through
 * {@code AssessmentFactory.create(type, settings)}.
 */
public abstract class PracticeAssessment {

    public static final int MAX_QUESTIONS = 30;

    private String id = UUID.randomUUID().toString();
    /** "quiz" or "mock-exam"; also lets DataStore load the right subclass. */
    private String type;
    protected PracticeSettings settings;
    private String courseId;
    private String courseCode;
    private String title;
    private List<String> topics = new ArrayList<>();
    private List<Question> questions = new ArrayList<>();
    /** One entry per question; blank means skipped. Empty until the student submits. */
    private List<String> answers = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime submittedAt;
    /** Null until graded (F08); may still contain pending short answers. */
    private GradeResult result;

    protected PracticeAssessment(String type, PracticeSettings settings) {
        this.type = type;
        this.settings = settings;
    }

    /**
     * The template method: the steps every practice goes through, always in this order.
     * Final so subclasses can change the steps but not the order.
     */
    public final void build(Course course, QuestionGenerator generator) {
        applySettings(course);
        List<String> chosen = selectTopics(course);
        if (chosen.isEmpty()) throw new IllegalStateException("No topics to practise for " + course.getCode());
        topics = new ArrayList<>(chosen);
        questions = new ArrayList<>(generateQuestions(course, topics, generator));
        if (questions.isEmpty()) throw new IllegalStateException("No questions could be generated");
        title = makeTitle();
        createdAt = LocalDateTime.now();
    }

    /** Shared step: checks the settings and links this practice to the course. Subclasses may add checks. */
    protected void applySettings(Course course) {
        if (settings == null) throw new IllegalArgumentException("Practice settings are required");
        int n = settings.getNumQuestions();
        if (n < 1 || n > MAX_QUESTIONS) {
            throw new IllegalArgumentException("Number of questions must be between 1 and " + MAX_QUESTIONS);
        }
        if (!PracticeSettings.DIFFICULTIES.contains(settings.getDifficulty())) {
            throw new IllegalArgumentException("Difficulty must be one of " + PracticeSettings.DIFFICULTIES);
        }
        courseId = course.getId();
        courseCode = course.getCode();
        warnings.clear();
    }

    /** Step 2: which topics this practice covers. */
    protected abstract List<String> selectTopics(Course course);

    /** Step 3: the questions for those topics. */
    protected abstract List<Question> generateQuestions(Course course, List<String> topics,
                                                        QuestionGenerator generator);

    protected abstract String makeTitle();

    protected void addWarning(String warning) {
        warnings.add(warning);
    }

    /**
     * Splits {@code total} questions across topics in proportion to their weights, using largest
     * remainders so the counts add up exactly. Topics that end up with 0 questions are left out.
     */
    protected static Map<String, Integer> distribute(Map<String, Double> weights, int total) {
        double sum = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (sum <= 0) return counts;
        Map<String, Double> remainders = new LinkedHashMap<>();
        int given = 0;
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            double exact = total * e.getValue() / sum;
            counts.put(e.getKey(), (int) Math.floor(exact));
            remainders.put(e.getKey(), exact - Math.floor(exact));
            given += (int) Math.floor(exact);
        }
        List<String> byRemainder = new ArrayList<>(remainders.keySet());
        byRemainder.sort((a, b) -> Double.compare(remainders.get(b), remainders.get(a)));
        for (int i = 0; given < total; i++, given++) {
            String topic = byRemainder.get(i % byRemainder.size());
            counts.put(topic, counts.get(topic) + 1);
        }
        counts.values().removeIf(c -> c == 0);
        return counts;
    }

    /** Stores the student's answers (one per question, blank for skipped) and marks the practice submitted. */
    public void submitAnswers(List<String> answers) {
        if (answers == null || answers.size() != questions.size()) {
            throw new IllegalArgumentException("Expected " + questions.size() + " answers");
        }
        if (isSubmitted()) throw new IllegalStateException("This practice was already submitted");
        this.answers = new ArrayList<>();
        for (String a : answers) this.answers.add(a == null ? "" : a.trim());
        submittedAt = LocalDateTime.now();
    }

    public int getAnsweredCount() {
        return (int) answers.stream().filter(a -> !a.isBlank()).count();
    }

    public boolean isSubmitted() { return submittedAt != null; }

    /** Stores the grading result (replacing an earlier one that had pending answers). */
    public void setResult(GradeResult result) {
        if (!isSubmitted()) throw new IllegalStateException("Submit the answers before grading");
        this.result = result;
    }

    public GradeResult getResult() { return result; }

    /** Fully graded: a result exists and nothing is pending. */
    public boolean isGraded() { return result != null && result.isComplete(); }

    /** Submitted but not graded yet, or graded with short answers still pending. */
    public boolean needsGrading() { return isSubmitted() && !isGraded(); }

    /** Only mock exams are timed; 0 means no time limit. */
    public int getTimeLimitMinutes() { return 0; }

    public String getId() { return id; }
    public String getType() { return type; }
    public PracticeSettings getSettings() { return settings; }
    public String getCourseId() { return courseId; }
    public String getCourseCode() { return courseCode; }
    public String getTitle() { return title; }
    public List<String> getTopics() { return Collections.unmodifiableList(topics); }
    public List<Question> getQuestions() { return Collections.unmodifiableList(questions); }
    public List<String> getAnswers() { return Collections.unmodifiableList(answers); }
    public List<String> getWarnings() { return Collections.unmodifiableList(warnings); }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }

    @Override
    public String toString() {
        String status;
        if (!isSubmitted()) status = "not submitted";
        else if (result == null) status = getAnsweredCount() + "/" + questions.size() + " answered, awaiting grading";
        else status = "score " + result;
        return title + " (" + courseCode + ", " + questions.size() + " questions, " + status + ")";
    }
}
