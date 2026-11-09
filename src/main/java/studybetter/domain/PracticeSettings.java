package studybetter.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * The options a student picks before generating practice (F06 quiz, F07 mock exam).
 * A plain settings object passed to {@code AssessmentFactory.create(type, settings)}; the assessment
 * validates it in its shared {@code applySettings()} step. Quiz uses the topic fields, MockExam uses
 * the target exam, week limit and time limit.
 */
public class PracticeSettings {

    public static final List<String> DIFFICULTIES = List.of("easy", "medium", "hard");

    private String courseCode;
    private int numQuestions = 5;
    private String difficulty = "medium";

    // quiz
    private List<String> topics = new ArrayList<>();
    private boolean useWeakTopics;
    /** Filled in by the controller from MasteryTracker when {@code useWeakTopics} is set. */
    private List<String> weakTopics = new ArrayList<>();

    // mock exam
    private String targetExam;
    private Integer maxWeek;
    private int timeLimitMinutes = 60;

    public PracticeSettings(String courseCode) {
        this.courseCode = courseCode;
    }

    public String getCourseCode() { return courseCode; }
    public int getNumQuestions() { return numQuestions; }
    public String getDifficulty() { return difficulty; }
    public List<String> getTopics() { return topics; }
    public boolean isUseWeakTopics() { return useWeakTopics; }
    public List<String> getWeakTopics() { return weakTopics; }
    public String getTargetExam() { return targetExam; }
    public Integer getMaxWeek() { return maxWeek; }
    public int getTimeLimitMinutes() { return timeLimitMinutes; }

    public PracticeSettings setNumQuestions(int n) { this.numQuestions = n; return this; }

    public PracticeSettings setDifficulty(String difficulty) {
        this.difficulty = difficulty == null ? null : difficulty.trim().toLowerCase();
        return this;
    }

    public PracticeSettings setTopics(List<String> topics) {
        this.topics = new ArrayList<>(topics);
        return this;
    }

    public PracticeSettings setUseWeakTopics(boolean useWeakTopics) { this.useWeakTopics = useWeakTopics; return this; }

    public PracticeSettings setWeakTopics(List<String> weakTopics) {
        this.weakTopics = new ArrayList<>(weakTopics);
        return this;
    }

    public PracticeSettings setTargetExam(String targetExam) { this.targetExam = targetExam; return this; }
    public PracticeSettings setMaxWeek(Integer maxWeek) { this.maxWeek = maxWeek; return this; }
    public PracticeSettings setTimeLimitMinutes(int minutes) { this.timeLimitMinutes = minutes; return this; }
}
