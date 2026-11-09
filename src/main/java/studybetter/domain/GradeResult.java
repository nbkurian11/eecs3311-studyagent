package studybetter.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The graded result of a quiz, mock exam or past test (F08): one {@link QuestionResult} per question, each
 * tagged with its topic so {@code MasteryTracker} can update concept mastery (F09).
 * A result can be "pending" when a short answer could not be graded yet (LLM unavailable); pending results
 * are left out of the score and of mastery until they are graded.
 */
public class GradeResult {

    /**
     * One graded question. {@code points} out of {@code maxPoints} allows partial credit (short answers, past tests).
     * {@code studentAnswer} and {@code correctAnswer} are for display and may be null.
     */
    public record QuestionResult(String question, String topic, String studentAnswer, String correctAnswer,
                                 double points, double maxPoints, String feedback, boolean pending) {

        /** Simple right/wrong result worth 1 point. */
        public QuestionResult(String topic, boolean correct, String feedback) {
            this(null, topic, null, null, correct ? 1 : 0, 1, feedback, false);
        }

        /** A question that still has to be graded. */
        public static QuestionResult pending(String question, String topic, String studentAnswer,
                                             String correctAnswer, double maxPoints, String reason) {
            return new QuestionResult(question, topic, studentAnswer, correctAnswer, 0, maxPoints, reason, true);
        }

        /** Full marks. */
        public boolean correct() { return !pending && points >= maxPoints; }

        /** Share of the marks earned, from 0 to 1. */
        public double fraction() { return maxPoints <= 0 ? 0 : points / maxPoints; }
    }

    /** Points earned per topic, for the per-topic breakdown. */
    public record TopicScore(String topic, double points, double maxPoints) {
        public double percent() { return maxPoints <= 0 ? 0 : 100 * points / maxPoints; }
    }

    private final String courseId;
    private final String title;
    private final List<QuestionResult> results;
    private final List<String> warnings = new ArrayList<>();
    private final LocalDateTime gradedAt;

    public GradeResult(String courseId, List<QuestionResult> results) {
        this(courseId, null, results);
    }

    public GradeResult(String courseId, String title, List<QuestionResult> results) {
        this.courseId = courseId;
        this.title = title;
        this.results = new ArrayList<>(results);
        this.gradedAt = LocalDateTime.now();
    }

    public void addWarning(String warning) { warnings.add(warning); }

    public String getCourseId() { return courseId; }
    public String getTitle() { return title; }
    public List<QuestionResult> getResults() { return List.copyOf(results); }
    public List<String> getWarnings() { return List.copyOf(warnings); }
    public LocalDateTime getGradedAt() { return gradedAt; }

    /** Points earned on the graded (not pending) questions. */
    public double getPoints() {
        return results.stream().filter(r -> !r.pending()).mapToDouble(QuestionResult::points).sum();
    }

    /** Points available on the graded (not pending) questions. */
    public double getMaxPoints() {
        return results.stream().filter(r -> !r.pending()).mapToDouble(QuestionResult::maxPoints).sum();
    }

    public double getPercent() {
        return getMaxPoints() <= 0 ? 0 : 100 * getPoints() / getMaxPoints();
    }

    public int getPendingCount() {
        return (int) results.stream().filter(QuestionResult::pending).count();
    }

    /** True when every question has been graded. */
    public boolean isComplete() { return getPendingCount() == 0; }

    /** Score per topic over graded questions, in the order topics first appear. Untagged questions are left out. */
    public List<TopicScore> getTopicScores() {
        Map<String, double[]> totals = new LinkedHashMap<>();
        for (QuestionResult r : results) {
            if (r.pending() || r.topic() == null || r.topic().isBlank()) continue;
            double[] t = totals.computeIfAbsent(r.topic(), k -> new double[2]);
            t[0] += r.points();
            t[1] += r.maxPoints();
        }
        List<TopicScore> scores = new ArrayList<>();
        totals.forEach((topic, t) -> scores.add(new TopicScore(topic, t[0], t[1])));
        return scores;
    }

    /** Topics where marks were lost on a graded question. */
    public List<String> getWeakTopics() {
        return results.stream()
                .filter(r -> !r.pending() && !r.correct() && r.topic() != null)
                .map(QuestionResult::topic)
                .distinct()
                .toList();
    }

    /**
     * The results graded now that were not graded in {@code previous} (all of them if previous is null),
     * so a re-grade of pending answers does not count the other answers toward mastery twice.
     */
    public GradeResult newlyGradedSince(GradeResult previous) {
        List<QuestionResult> fresh = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            boolean wasPending = previous == null || i >= previous.results.size() || previous.results.get(i).pending();
            if (wasPending && !results.get(i).pending()) fresh.add(results.get(i));
        }
        return new GradeResult(courseId, title, fresh);
    }

    @Override
    public String toString() {
        String score = String.format("%s/%s (%.0f%%)", formatPoints(getPoints()), formatPoints(getMaxPoints()),
                getPercent());
        return getPendingCount() == 0 ? score : score + ", " + getPendingCount() + " pending";
    }

    /** 3.0 prints as "3", 2.5 as "2.5". */
    public static String formatPoints(double points) {
        return points == Math.floor(points) ? String.valueOf((long) points) : String.format("%.1f", points);
    }
}
