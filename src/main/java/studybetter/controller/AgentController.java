package studybetter.controller;

import studybetter.domain.AssessmentItem;
import studybetter.domain.ConceptMastery;
import studybetter.domain.Course;
import studybetter.domain.StudySession;
import studybetter.observer.DataObserver;
import studybetter.service.CourseManager;
import studybetter.service.DeadlineTracker;
import studybetter.service.DeadlineTracker.Deadline;
import studybetter.service.GradeBook;
import studybetter.service.MasteryTracker;
import studybetter.service.SessionLogger;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;


public class AgentController {

    public record DeadlineReport(List<Deadline> upcoming, List<Deadline> dueSoon,
                                 List<Deadline> overdue, List<Deadline> undated) {}

    private final CourseManager courseManager;
    private final DeadlineTracker deadlineTracker;
    private final GradeBook gradeBook;
    private final MasteryTracker masteryTracker;
    private final SessionLogger sessionLogger;

    public AgentController() {
        this(new CourseManager(), new DeadlineTracker(), new GradeBook(),
                new MasteryTracker(), new SessionLogger());
    }

    public AgentController(CourseManager courseManager, DeadlineTracker deadlineTracker,
                           GradeBook gradeBook, MasteryTracker masteryTracker, SessionLogger sessionLogger) {
        this.courseManager = courseManager;
        this.deadlineTracker = deadlineTracker;
        this.gradeBook = gradeBook;
        this.masteryTracker = masteryTracker;
        this.sessionLogger = sessionLogger;
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