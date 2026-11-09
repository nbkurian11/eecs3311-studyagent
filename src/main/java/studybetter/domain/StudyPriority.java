package studybetter.domain;

import java.time.LocalDate;

/**
 * Something the student should spend time on, ranked by the agent while planning (F12): an upcoming assessment
 * ({@code dueDate} and {@code weight} set) or a weak topic ({@code dueDate} null). Higher {@code score} = more urgent.
 * {@code reason} explains the score in plain words ("due in 3 days, worth 20%; 2 weak topics").
 */
public record StudyPriority(String courseCode, String title, LocalDate dueDate, double weight,
                            double score, String reason) {

    public boolean isDeadline() { return dueDate != null; }

    @Override
    public String toString() {
        return String.format("%s: %s (%s)", courseCode, title, reason);
    }
}
