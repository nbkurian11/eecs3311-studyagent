package studybetter.service;

import studybetter.domain.ConceptMastery;
import studybetter.domain.Course;
import studybetter.observer.DataObserver;
import studybetter.observer.Subject;
import studybetter.service.DeadlineTracker.Deadline;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

/**
 * F11: the progress dashboard. Observer pattern on both sides:
 * <ul>
 *   <li>as a {@link DataObserver} it is attached to GradeBook, MasteryTracker, SessionLogger and DeadlineTracker
 *       (through {@code AgentController.addObserver}); whenever one of them reports a change it recomputes
 *       its {@link Stats};</li>
 *   <li>as a {@link Subject} it then tells its own observers (the GUI's Dashboard tab) to redraw.</li>
 * </ul>
 * The stats are an immutable snapshot, so the UI never reads half-updated numbers.
 */
public class ProgressDashboard extends Subject implements DataObserver {

    public static final int WEAKEST_TOPICS_SHOWN = 5;
    public static final int DEADLINES_SHOWN = 8;

    /** Study minutes and current grade (null when nothing is graded) for one course. */
    public record CourseStats(String courseCode, int studyMinutes, Double currentGrade) {}

    /** One tested topic with its mastery level from 0 to 1. */
    public record TopicStats(String courseCode, String topic, double level, String label) {}

    /** Everything the dashboard shows, computed at {@code updatedAt} because of {@code lastEvent}. */
    public record Stats(List<CourseStats> courses, List<TopicStats> weakestTopics,
                        List<Deadline> upcoming, List<Deadline> completed,
                        int totalStudyMinutes, String lastEvent, LocalDateTime updatedAt) {}

    private final CourseManager courseManager;
    private final DeadlineTracker deadlineTracker;
    private final GradeBook gradeBook;
    private final MasteryTracker masteryTracker;
    private final SessionLogger sessionLogger;
    private volatile Stats stats;

    public ProgressDashboard(CourseManager courseManager, DeadlineTracker deadlineTracker, GradeBook gradeBook,
                             MasteryTracker masteryTracker, SessionLogger sessionLogger) {
        this.courseManager = courseManager;
        this.deadlineTracker = deadlineTracker;
        this.gradeBook = gradeBook;
        this.masteryTracker = masteryTracker;
        this.sessionLogger = sessionLogger;
        refresh("startup");
    }

    /** Called by a service after it changes data ("grade", "session", "mastery", "deadline"). */
    @Override
    public void onDataChanged(String event) {
        refresh(event);
        notifyObservers("dashboard");
    }

    /** The latest snapshot. */
    public Stats getStats() {
        return stats;
    }

    /** Recomputes the snapshot from the services (also used when the user opens the tab). */
    public void refresh(String reason) {
        List<CourseStats> courses = new ArrayList<>();
        List<TopicStats> topics = new ArrayList<>();
        for (Course c : courseManager.getAllCourses()) {
            OptionalDouble grade = gradeBook.currentGrade(c.getId());
            courses.add(new CourseStats(c.getCode(), sessionLogger.totalMinutes(c.getId()),
                    grade.isPresent() ? grade.getAsDouble() : null));
            for (ConceptMastery m : masteryTracker.getMastery(c.getId())) {
                if (m.getAttempts() > 0) topics.add(new TopicStats(c.getCode(), m.getTopic(), m.getLevel(), m.getLabel()));
            }
        }
        topics.sort(Comparator.comparingDouble(TopicStats::level));
        stats = new Stats(courses,
                topics.stream().limit(WEAKEST_TOPICS_SHOWN).toList(),
                deadlineTracker.getUpcoming(null).stream().limit(DEADLINES_SHOWN).toList(),
                deadlineTracker.getCompleted(null).stream().limit(DEADLINES_SHOWN).toList(),
                sessionLogger.totalMinutes(null),
                reason, LocalDateTime.now());
    }
}
