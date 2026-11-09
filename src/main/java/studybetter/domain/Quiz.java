package studybetter.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F06: a short practice quiz on topics the student chooses, or on their weak topics.
 * Concrete class in the Template Method pattern: fills in the topic and question steps of
 * {@link PracticeAssessment#build}. The questions are split evenly across the topics.
 */
public class Quiz extends PracticeAssessment {

    public static final String TYPE = "quiz";

    public Quiz(PracticeSettings settings) {
        super(TYPE, settings);
    }

    /** Used by Gson when loading saved data. */
    private Quiz() {
        super(TYPE, null);
    }

    /**
     * The weak topics from mastery data if the student asked for them, otherwise the chosen topics.
     * A chosen name that matches a course topic takes the course's spelling; other names are allowed
     * so the student can practise any concept.
     */
    @Override
    protected List<String> selectTopics(Course course) {
        if (settings.isUseWeakTopics()) {
            if (settings.getWeakTopics().isEmpty()) {
                throw new IllegalStateException("You have no weak topics in " + course.getCode()
                        + " yet. Take a quiz on chosen topics first.");
            }
            return new ArrayList<>(settings.getWeakTopics());
        }
        List<String> selected = new ArrayList<>();
        for (String name : settings.getTopics()) {
            if (name == null || name.isBlank()) continue;
            String topic = course.getTopics().stream()
                    .map(Topic::getName)
                    .filter(t -> t.equalsIgnoreCase(name.trim()))
                    .findFirst()
                    .orElse(name.trim());
            if (selected.stream().noneMatch(topic::equalsIgnoreCase)) selected.add(topic);
        }
        if (selected.isEmpty()) throw new IllegalArgumentException("Choose at least one topic");
        return selected;
    }

    /** One request to the generator for all topics, with the questions split evenly. */
    @Override
    protected List<Question> generateQuestions(Course course, List<String> topics, QuestionGenerator generator) {
        int wanted = settings.getNumQuestions();
        Map<String, Double> equal = new LinkedHashMap<>();
        topics.forEach(t -> equal.put(t, 1.0));
        Map<String, Integer> counts = distribute(equal, wanted);
        if (counts.size() < topics.size()) {
            addWarning("Only " + wanted + " question(s) for " + topics.size()
                    + " topics, so some topics are not covered.");
        }
        QuestionGenerator.QuestionBatch batch = generator.generateQuestions(
                course, counts, settings.getDifficulty(), "a short practice quiz");
        batch.warnings().forEach(this::addWarning);
        if (!batch.questions().isEmpty() && batch.questions().size() < wanted) {
            addWarning("Only " + batch.questions().size() + " of " + wanted + " questions were usable.");
        }
        return batch.questions();
    }

    @Override
    protected String makeTitle() {
        return "Quiz: " + String.join(", ", getTopics());
    }
}
