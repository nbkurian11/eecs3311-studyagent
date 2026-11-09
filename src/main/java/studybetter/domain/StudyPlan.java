package studybetter.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * F12: a one-week study schedule built by the agent. Holds the ranked priorities it was based on, the tasks,
 * and notes (e.g. "AI unavailable, simple plan"). {@code fromAi} is false for the deterministic fallback plan.
 * The agent marks the plan outdated when grades, mastery, sessions or deadlines change after it was made.
 */
public class StudyPlan {

    private final LocalDate startDate;
    private final int days;
    private final int minutesPerDay;
    private final List<StudyPriority> priorities;
    private final List<StudyTask> tasks;
    private final boolean fromAi;
    private final List<String> notes = new ArrayList<>();
    private final LocalDateTime createdAt = LocalDateTime.now();
    private volatile String outdatedBecause;

    public StudyPlan(LocalDate startDate, int days, int minutesPerDay, List<StudyPriority> priorities,
                     List<StudyTask> tasks, boolean fromAi) {
        this.startDate = startDate;
        this.days = days;
        this.minutesPerDay = minutesPerDay;
        this.priorities = List.copyOf(priorities);
        this.tasks = List.copyOf(tasks);
        this.fromAi = fromAi;
    }

    public void addNote(String note) { notes.add(note); }

    /** Called when data the plan was based on changes; {@code event} is e.g. "grade" or "session". */
    public void markOutdated(String event) {
        if (outdatedBecause == null) outdatedBecause = event;
    }

    public boolean isOutdated() { return outdatedBecause != null; }

    /** The first change that made the plan outdated, or null. */
    public String getOutdatedBecause() { return outdatedBecause; }

    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return startDate.plusDays(days - 1L); }
    public int getMinutesPerDay() { return minutesPerDay; }
    public List<StudyPriority> getPriorities() { return priorities; }
    public List<StudyTask> getTasks() { return tasks; }
    public boolean isFromAi() { return fromAi; }
    public List<String> getNotes() { return List.copyOf(notes); }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public int minutesOn(LocalDate date) {
        return tasks.stream().filter(t -> t.date().equals(date)).mapToInt(StudyTask::minutes).sum();
    }

    public int getTotalMinutes() {
        return tasks.stream().mapToInt(StudyTask::minutes).sum();
    }
}
