package studybetter.agent;

import studybetter.domain.ConceptMastery;
import studybetter.domain.Course;
import studybetter.domain.StudySession;
import studybetter.service.CourseManager;
import studybetter.service.GradeBook;
import studybetter.service.MasteryTracker;
import studybetter.service.SessionLogger;

import java.time.LocalDateTime;
import java.util.List;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

/**
 * Tool that reports how the student is doing in each course: current grade (GradeBook), weak concepts
 * (MasteryTracker) and recent study minutes (SessionLogger). The agent uses it, together with
 * {@link DeadlineTool}, when building a study plan (F12).
 */
public class ProgressTool implements Tool {

    /** One course's progress. {@code currentGrade} is null when nothing is graded. */
    public record CourseProgress(Course course, Double currentGrade, List<ConceptMastery> weakConcepts,
                                 int recentMinutes) {}

    private final CourseManager courseManager;
    private final GradeBook gradeBook;
    private final MasteryTracker masteryTracker;
    private final SessionLogger sessionLogger;

    public ProgressTool(CourseManager courseManager, GradeBook gradeBook, MasteryTracker masteryTracker,
                        SessionLogger sessionLogger) {
        this.courseManager = courseManager;
        this.gradeBook = gradeBook;
        this.masteryTracker = masteryTracker;
        this.sessionLogger = sessionLogger;
    }

    @Override
    public String getName() { return "progress"; }

    @Override
    public String getDescription() {
        return "Current grade, weak concepts and study minutes in the last 7 days for every course. Input: ignored";
    }

    @Override
    public String execute(String input) {
        List<CourseProgress> all = progress(7);
        if (all.isEmpty()) return "No courses.";
        return all.stream().map(ProgressTool::describe).collect(Collectors.joining("\n"));
    }

    /** Progress for every course, counting study minutes from sessions started in the last {@code recentDays}. */
    public List<CourseProgress> progress(int recentDays) {
        LocalDateTime since = LocalDateTime.now().minusDays(recentDays);
        return courseManager.getAllCourses().stream().map(c -> {
            OptionalDouble grade = gradeBook.currentGrade(c.getId());
            int minutes = sessionLogger.getSessions(c.getId()).stream()
                    .filter(s -> s.getStart().isAfter(since))
                    .mapToInt(StudySession::getMinutes).sum();
            return new CourseProgress(c, grade.isPresent() ? grade.getAsDouble() : null,
                    masteryTracker.getWeakConcepts(c.getId()), minutes);
        }).toList();
    }

    /** "EECS3311: grade 84.0%, weak: Facade (0%), 95 min in the last 7 days". */
    public static String describe(CourseProgress p) {
        String grade = p.currentGrade() == null ? "no grades yet" : String.format("grade %.1f%%", p.currentGrade());
        String weak = p.weakConcepts().isEmpty() ? "no weak topics"
                : "weak: " + p.weakConcepts().stream()
                        .map(m -> String.format("%s (%.0f%%)", m.getTopic(), m.getLevel() * 100))
                        .collect(Collectors.joining(", "));
        return p.course().getCode() + ": " + grade + ", " + weak + ", " + p.recentMinutes() + " min in the last 7 days";
    }
}
