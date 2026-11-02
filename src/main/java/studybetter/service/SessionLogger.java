package studybetter.service;

import studybetter.domain.StudySession;
import studybetter.observer.Subject;
import studybetter.persistence.DataStore;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public class SessionLogger extends Subject {
    public static final int MAX_REASONABLE_MINUTES = 6 * 60;

    private final Supplier<LocalDateTime> clock;
    private StudySession activeSession;

    public SessionLogger() {
        this(LocalDateTime::now);
    }

    /** Lets tests control the time. */
    public SessionLogger(Supplier<LocalDateTime> clock) {
        this.clock = clock;
        // restore a session that was still running when the app closed
        activeSession = store().getSessions().stream()
                .filter(StudySession::isActive).findFirst().orElse(null);
    }

    public StudySession start(String courseId, String topic) {
        if (activeSession != null) {
            throw new IllegalStateException("A session is already running: " + activeSession.getTopic());
        }
        requireCourse(courseId);
        activeSession = new StudySession(courseId, topic, clock.get());
        store().getSessions().add(activeSession);   // saved immediately so it survives a crash
        store().saveAll();
        return activeSession;
    }

    public StudySession end() {
        if (activeSession == null) {
            throw new IllegalStateException("No session is running");
        }
        StudySession finished = activeSession;
        finished.finish(clock.get());
        activeSession = null;
        store().saveAll();
        notifyObservers("session");
        return finished;
    }

    public StudySession addPastSession(String courseId, String topic, LocalDateTime start, LocalDateTime end) {
        requireCourse(courseId);
        StudySession s = new StudySession(courseId, topic, start);
        s.finish(end);                               // rejects end before start
        store().getSessions().add(s);
        store().saveAll();
        notifyObservers("session");
        return s;
    }

    /** True if the session is suspiciously long and the user should confirm it. */
    public boolean needsConfirmation(StudySession s) {
        return s.getMinutes() > MAX_REASONABLE_MINUTES;
    }

    public Optional<StudySession> getActiveSession() {
        return Optional.ofNullable(activeSession);
    }

    public List<StudySession> getSessions(String courseId) {
        return store().getSessions().stream()
                .filter(s -> !s.isActive())
                .filter(s -> courseId == null || s.getCourseId().equals(courseId))
                .toList();
    }

    public int totalMinutes(String courseId) {
        return getSessions(courseId).stream().mapToInt(StudySession::getMinutes).sum();
    }

    private void requireCourse(String courseId) {
        boolean exists = store().getCourses().stream().anyMatch(c -> c.getId().equals(courseId));
        if (!exists) throw new IllegalArgumentException("No course with id " + courseId);
    }

    private DataStore store() {
        return DataStore.getInstance();
    }
}