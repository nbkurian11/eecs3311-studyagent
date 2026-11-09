package studybetter.agent;

import studybetter.service.DeadlineTracker;
import studybetter.service.DeadlineTracker.Deadline;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Tool that reports upcoming deadlines and their weights, using {@link DeadlineTracker}.
 * The agent uses it to know what is due soon (e.g. when planning study time).
 */
public class DeadlineTool implements Tool {
    private final DeadlineTracker deadlineTracker;

    public DeadlineTool(DeadlineTracker deadlineTracker) {
        this.deadlineTracker = deadlineTracker;
    }

    @Override
    public String getName() { return "deadlines"; }

    @Override
    public String getDescription() {
        return "Lists upcoming, not-completed assessments with due dates and weights. Input: course code or blank for all";
    }

    @Override
    public String execute(String input) {
        List<Deadline> deadlines = upcoming(input);
        if (deadlines.isEmpty()) return "No upcoming deadlines.";
        LocalDate today = LocalDate.now();
        StringBuilder sb = new StringBuilder();
        for (Deadline d : deadlines) {
            long days = ChronoUnit.DAYS.between(today, d.item().getDueDate());
            sb.append(d).append(" | in ").append(days).append(" day(s)\n");
        }
        return sb.toString().trim();
    }

    /** Upcoming deadlines, soonest first. Blank or null input means all courses. */
    public List<Deadline> upcoming(String courseCode) {
        return deadlineTracker.getUpcoming(courseCode == null || courseCode.isBlank() ? null : courseCode);
    }
}
