package studybetter.agent;

import studybetter.agent.ProgressTool.CourseProgress;
import studybetter.domain.ConceptMastery;
import studybetter.domain.StudyPriority;
import studybetter.domain.StudyTask;
import studybetter.service.DeadlineTracker.Deadline;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The deterministic part of study planning (F12), used by {@link StudyAgent#buildStudyPlan}:
 * <ul>
 *   <li>{@link #rank}: scores what to study: deadline urgency × weight, boosted for courses with weak topics,
 *       a low grade or little recent study, plus weak topics on their own;</li>
 *   <li>{@link #fallbackSchedule}: a simple plan without the LLM, giving time in order of score;</li>
 *   <li>{@link #fitToDailyLimit}: trims any schedule (also the LLM's) so no day goes over the hours available.</li>
 * </ul>
 */
final class PlanScheduler {

    /** Deadlines further away than this are left out of a one-week plan. */
    static final int HORIZON_DAYS = 21;
    static final int MIN_BLOCK = 15;
    static final int MAX_BLOCK = 60;
    private static final int WEAK_TOPICS_PER_COURSE = 3;

    private PlanScheduler() {}

    /** Ranks upcoming deadlines (within the horizon) and weak topics, highest score first. */
    static List<StudyPriority> rank(List<Deadline> upcoming, List<CourseProgress> progress, LocalDate today) {
        Map<String, CourseProgress> byCourse = new HashMap<>();
        progress.forEach(p -> byCourse.put(p.course().getCode(), p));
        List<StudyPriority> ranked = new ArrayList<>();

        for (Deadline d : upcoming) {
            long days = ChronoUnit.DAYS.between(today, d.item().getDueDate());
            if (days < 0 || days > HORIZON_DAYS) continue;
            double weight = d.item().getWeight();
            // "deadline x weight": an item worth 20% due in 6 days scores 20, due tomorrow 70
            double score = weight * 7.0 / (days + 1);
            List<String> why = new ArrayList<>();
            why.add((days == 0 ? "due today" : days == 1 ? "due tomorrow" : "due in " + days + " days")
                    + String.format(", worth %.0f%%", weight));
            CourseProgress p = byCourse.get(d.course().getCode());
            if (p != null) {
                if (!p.weakConcepts().isEmpty()) {
                    score *= 1.25;
                    why.add(p.weakConcepts().size() + " weak topic(s): " + topicNames(p.weakConcepts()));
                }
                if (p.currentGrade() != null && p.currentGrade() < 70) {
                    score *= 1.2;
                    why.add(String.format("current grade %.0f%%", p.currentGrade()));
                }
                if (p.recentMinutes() < 60) {
                    score *= 1.1;
                    why.add("only " + p.recentMinutes() + " min studied this week");
                }
            }
            ranked.add(new StudyPriority(d.course().getCode(), d.item().getTitle(), d.item().getDueDate(),
                    weight, round(score), String.join("; ", why)));
        }

        for (CourseProgress p : progress) {
            for (ConceptMastery m : p.weakConcepts().stream().limit(WEAK_TOPICS_PER_COURSE).toList()) {
                double score = 2 + (0.5 - m.getLevel()) * 20; // 2 (almost developing) to 12 (0% mastery)
                ranked.add(new StudyPriority(p.course().getCode(), "Review " + m.getTopic(), null, 0, round(score),
                        String.format("weak topic, mastery %.0f%% after %d attempt(s)", m.getLevel() * 100,
                                m.getAttempts())));
            }
        }
        ranked.sort(Comparator.comparingDouble(StudyPriority::score).reversed());
        return ranked;
    }

    /**
     * Plan without the LLM. Each priority gets a share of the week's minutes in proportion to its score
     * (at least {@value #MIN_BLOCK} min); time a priority cannot use before its deadline goes to the others.
     * Then each day is filled in score order, one block per priority per day, never after the day before an
     * assessment is due. Blocks are up to {@value #MAX_BLOCK} min, or longer when few days are left for that item.
     */
    static List<StudyTask> fallbackSchedule(List<StudyPriority> priorities, Map<String, List<String>> weakTopics,
                                            LocalDate start, int days, int minutesPerDay) {
        LocalDate end = start.plusDays(days - 1L);
        double totalScore = priorities.stream().mapToDouble(StudyPriority::score).sum();
        int weekMinutes = minutesPerDay * days;
        Map<StudyPriority, Integer> budget = new LinkedHashMap<>();
        int excess = 0;
        double openScore = 0; // score of priorities that can take more time
        for (StudyPriority p : priorities) {
            int share = totalScore <= 0 ? 0 : (int) Math.round(weekMinutes * p.score() / totalScore);
            int usable = daysAvailable(p, start, end) * minutesPerDay;
            if (share > usable) {
                excess += share - usable;
                share = usable;
            } else {
                openScore += p.score();
            }
            budget.put(p, share);
        }
        for (StudyPriority p : priorities) {
            int usable = daysAvailable(p, start, end) * minutesPerDay;
            int share = budget.get(p);
            if (share < usable && openScore > 0) share += (int) Math.round(excess * p.score() / openScore);
            budget.put(p, Math.max(MIN_BLOCK, roundTo15(Math.min(share, usable))));
        }

        List<StudyTask> tasks = new ArrayList<>();
        for (int d = 0; d < days; d++) {
            LocalDate date = start.plusDays(d);
            int capacity = minutesPerDay;
            for (StudyPriority p : priorities) {
                if (capacity < MIN_BLOCK) break;
                int left = budget.get(p);
                if (left <= 0 || date.isAfter(lastStudyDay(p, start))) continue;
                // deadlines with few days left may take longer blocks; weak-topic review stays at most an hour
                int maxBlock = p.isDeadline()
                        ? Math.max(MAX_BLOCK, (int) Math.ceil((double) left / daysAvailable(p, date, end)))
                        : MAX_BLOCK;
                int block = roundDownTo15(Math.min(Math.min(maxBlock, left), capacity));
                // skip tiny slivers unless that is all the time this priority has left
                if (block < MIN_BLOCK || (block < 2 * MIN_BLOCK && left >= 2 * MIN_BLOCK)) continue;
                tasks.add(new StudyTask(date, p.courseCode(), describe(p, weakTopics), block, p.reason()));
                budget.put(p, left - block);
                capacity -= block;
            }
        }
        return tasks;
    }

    /**
     * Keeps tasks in order and drops or shortens the ones that would push a day over {@code minutesPerDay}.
     * Returns how many minutes were cut through {@code cut[0]}.
     */
    static List<StudyTask> fitToDailyLimit(List<StudyTask> tasks, int minutesPerDay, int[] cut) {
        Map<LocalDate, Integer> used = new HashMap<>();
        List<StudyTask> kept = new ArrayList<>();
        for (StudyTask t : tasks) {
            int room = minutesPerDay - used.getOrDefault(t.date(), 0);
            int minutes = Math.min(t.minutes(), room);
            if (minutes < MIN_BLOCK) minutes = 0;
            cut[0] += t.minutes() - minutes;
            if (minutes == 0) continue;
            kept.add(minutes == t.minutes() ? t
                    : new StudyTask(t.date(), t.courseCode(), t.description(), minutes, t.reason()));
            used.merge(t.date(), minutes, Integer::sum);
        }
        kept.sort(Comparator.comparing(StudyTask::date));
        return kept;
    }

    /** Study for an assessment up to the day before it is due (or today if it is due today). */
    static LocalDate lastStudyDay(StudyPriority p, LocalDate start) {
        if (!p.isDeadline()) return LocalDate.MAX;
        LocalDate dayBefore = p.dueDate().minusDays(1);
        return dayBefore.isBefore(start) ? start : dayBefore;
    }

    /** Days from {@code from} to {@code end} on which this priority can still be studied (at least 1). */
    private static int daysAvailable(StudyPriority p, LocalDate from, LocalDate end) {
        LocalDate last = lastStudyDay(p, from);
        if (last.isAfter(end)) last = end;
        return (int) Math.max(1, ChronoUnit.DAYS.between(from, last) + 1);
    }

    private static String describe(StudyPriority p, Map<String, List<String>> weakTopics) {
        if (!p.isDeadline()) return p.title() + " (then take a short quiz on it)";
        List<String> weak = weakTopics.getOrDefault(p.courseCode(), List.of());
        return "Prepare for " + p.title() + (weak.isEmpty() ? "" : ", focusing on " + String.join(", ", weak));
    }

    private static String topicNames(List<ConceptMastery> weak) {
        return weak.stream().limit(WEAK_TOPICS_PER_COURSE).map(ConceptMastery::getTopic).collect(Collectors.joining(", "));
    }

    private static double round(double score) {
        return Math.round(score * 10) / 10.0;
    }

    private static int roundTo15(int minutes) {
        return (int) Math.round(minutes / 15.0) * 15;
    }

    private static int roundDownTo15(int minutes) {
        return minutes / 15 * 15;
    }
}
