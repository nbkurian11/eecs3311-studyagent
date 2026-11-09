package studybetter.domain;

import studybetter.llm.LLMException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F07: a timed mock exam covering the course's syllabus topics, with the number of questions per topic
 * in proportion to the topic weights from the syllabus (an even split when the syllabus gives none).
 * Concrete class in the Template Method pattern. Questions are generated in small batches, so if the
 * AI fails partway the student can still take a shorter exam with what was generated.
 */
public class MockExam extends PracticeAssessment {

    public static final String TYPE = "mock-exam";
    private static final int QUESTIONS_PER_REQUEST = 8;
    private static final int MAX_TIME_LIMIT_MINUTES = 300;

    /** How many questions each topic should get (decided before generation). */
    private Map<String, Integer> questionsPerTopic = new LinkedHashMap<>();
    private boolean partial;

    public MockExam(PracticeSettings settings) {
        super(TYPE, settings);
    }

    /** Used by Gson when loading saved data. */
    private MockExam() {
        super(TYPE, null);
    }

    /** Shared checks plus the time limit, which only mock exams have. */
    @Override
    protected void applySettings(Course course) {
        super.applySettings(course);
        int minutes = settings.getTimeLimitMinutes();
        if (minutes < 1 || minutes > MAX_TIME_LIMIT_MINUTES) {
            throw new IllegalArgumentException("Time limit must be between 1 and " + MAX_TIME_LIMIT_MINUTES
                    + " minutes");
        }
        partial = false;
    }

    /**
     * All syllabus topics (up to the chosen week, if any), then the deterministic split:
     * topics with a weight keep it, topics without one share whatever percentage is left.
     */
    @Override
    protected List<String> selectTopics(Course course) {
        List<Topic> covered = new ArrayList<>();
        Integer maxWeek = settings.getMaxWeek();
        for (Topic t : course.getTopics()) {
            if (maxWeek == null || t.getWeek() <= 0 || t.getWeek() <= maxWeek) covered.add(t);
        }
        if (covered.isEmpty()) {
            throw new IllegalStateException(course.getCode() + " has no topics"
                    + (maxWeek == null ? "" : " up to week " + maxWeek)
                    + ". Import a syllabus first ('import-syllabus').");
        }

        double knownTotal = 0;
        int unknown = 0;
        for (Topic t : covered) {
            if (t.getWeight() != null) knownTotal += t.getWeight();
            else unknown++;
        }
        if (unknown == covered.size()) {
            addWarning("The syllabus gives no topic weights, so questions are split evenly across topics.");
        }
        double shareForUnknown = unknown == covered.size() ? 1.0 : Math.max(0, 100 - knownTotal) / Math.max(1, unknown);

        Map<String, Double> weights = new LinkedHashMap<>();
        for (Topic t : covered) weights.put(t.getName(), t.getWeight() != null ? t.getWeight() : shareForUnknown);
        questionsPerTopic = distribute(weights, settings.getNumQuestions());
        if (questionsPerTopic.size() < covered.size()) {
            addWarning((covered.size() - questionsPerTopic.size()) + " low-weight topic(s) got no questions. "
                    + "Ask for more questions to cover them.");
        }
        return new ArrayList<>(questionsPerTopic.keySet());
    }

    /** Generates the planned questions in batches; a failed batch is reported and skipped. */
    @Override
    protected List<Question> generateQuestions(Course course, List<String> topics, QuestionGenerator generator) {
        String purpose = "a full mock exam" + (settings.getTargetExam() == null ? ""
                : " that simulates the \"" + settings.getTargetExam() + "\"");
        List<Question> all = new ArrayList<>();
        LLMException lastError = null;
        for (Map<String, Integer> batch : batches()) {
            try {
                QuestionGenerator.QuestionBatch result =
                        generator.generateQuestions(course, batch, settings.getDifficulty(), purpose);
                result.warnings().forEach(this::addWarning);
                all.addAll(result.questions());
            } catch (LLMException e) {
                lastError = e;
                addWarning("Could not generate questions for " + String.join(", ", batch.keySet())
                        + " (" + e.getMessage() + ").");
            }
        }
        if (all.isEmpty() && lastError != null) throw lastError;
        if (!all.isEmpty() && all.size() < settings.getNumQuestions()) {
            partial = true;
            addWarning("Only " + all.size() + " of " + settings.getNumQuestions() + " questions were generated.");
        }
        return all;
    }

    /** Groups the planned topics into requests of at most {@value #QUESTIONS_PER_REQUEST} questions. */
    private List<Map<String, Integer>> batches() {
        List<Map<String, Integer>> batches = new ArrayList<>();
        Map<String, Integer> current = new LinkedHashMap<>();
        int size = 0;
        for (Map.Entry<String, Integer> e : questionsPerTopic.entrySet()) {
            if (size > 0 && size + e.getValue() > QUESTIONS_PER_REQUEST) {
                batches.add(current);
                current = new LinkedHashMap<>();
                size = 0;
            }
            current.put(e.getKey(), e.getValue());
            size += e.getValue();
        }
        if (!current.isEmpty()) batches.add(current);
        return batches;
    }

    @Override
    protected String makeTitle() {
        return "Mock exam" + (settings.getTargetExam() == null ? "" : ": " + settings.getTargetExam());
    }

    @Override
    public int getTimeLimitMinutes() { return settings.getTimeLimitMinutes(); }

    public Map<String, Integer> getQuestionsPerTopic() { return new LinkedHashMap<>(questionsPerTopic); }

    /** True when fewer questions were generated than planned (the student is offered a shorter exam). */
    public boolean isPartial() { return partial; }
}
