package studybetter.domain;

import java.time.LocalDate;

/**
 * One block of study in a {@link StudyPlan} (F12): what to study, for which course, on which day, for how long,
 * and why the agent chose it.
 */
public record StudyTask(LocalDate date, String courseCode, String description, int minutes, String reason) {}
