package studybetter.domain;

import java.util.ArrayList;
import java.util.List;

public class GradeResult {

    public record QuestionResult(String topic, boolean correct, String feedback) {}

    private final String courseId;
    private final List<QuestionResult> results;

    public GradeResult(String courseId, List<QuestionResult> results) {
        this.courseId = courseId;
        this.results = new ArrayList<>(results);
    }

    public String getCourseId() { return courseId; }
    public List<QuestionResult> getResults() { return List.copyOf(results); }

    public int getScore() { return (int) results.stream().filter(QuestionResult::correct).count(); }
    public int getTotal() { return results.size(); }

    public List<String> getWeakTopics() {
        return results.stream()
                .filter(r -> !r.correct() && r.topic() != null)
                .map(QuestionResult::topic)
                .distinct()
                .toList();
    }

    @Override
    public String toString() {
        return getScore() + "/" + getTotal() + " correct";
    }
}