package studybetter.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

public class StudySession {
    private final String id;
    private final String courseId;
    private final String topic;
    private final LocalDateTime start;
    private LocalDateTime end;

    public StudySession(String courseId, String topic, LocalDateTime start) {
        this.id = UUID.randomUUID().toString();
        this.courseId = courseId;
        this.topic = topic;
        this.start = start;
    }

    public void finish(LocalDateTime end) {
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("End time cannot be before start time");
        }
        this.end = end;
    }

    public boolean isActive() { return end == null; }

    public int getMinutes() {
        return end == null ? 0 : (int) Duration.between(start, end).toMinutes();
    }

    public String getId() { return id; }
    public String getCourseId() { return courseId; }
    public String getTopic() { return topic; }
    public LocalDateTime getStart() { return start; }
    public LocalDateTime getEnd() { return end; }

    @Override
    public String toString() {
        return topic + " | " + start.toLocalDate() + " | " + (isActive() ? "in progress" : getMinutes() + " min");
    }
}