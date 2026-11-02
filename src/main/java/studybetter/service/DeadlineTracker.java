package studybetter.service;

import studybetter.domain.AssessmentItem;
import studybetter.domain.Course;
import studybetter.persistence.DataStore;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

public class DeadlineTracker {

    /** An assessment together with the course it belongs to. */
    public record Deadline(Course course, AssessmentItem item) {
        @Override
        public String toString() {
            String due = item.getDueDate() == null ? "No date" : item.getDueDate().toString();
            return course.getCode() + " | " + item.getTitle() + " | due " + due
                    + " | " + item.getWeight() + "%" + (item.isCompleted() ? " | done" : "");
        }
    }

    private int dueSoonDays = 7;
    private final Supplier<LocalDate> today;

    public DeadlineTracker() {
        this(LocalDate::now);
    }

    /** Lets tests pretend it is a different day. */
    public DeadlineTracker(Supplier<LocalDate> today) {
        this.today = today;
    }

    public void setDueSoonDays(int days) {
        if (days < 0) throw new IllegalArgumentException("Days must be 0 or more");
        this.dueSoonDays = days;
    }

    /** Not completed, due today or later. Optional course code filter (null = all courses). */
    public List<Deadline> getUpcoming(String courseCodeFilter) {
        LocalDate now = today.get();
        return all(courseCodeFilter).stream()
                .filter(d -> !d.item().isCompleted())
                .filter(d -> d.item().getDueDate() != null && !d.item().getDueDate().isBefore(now))
                .sorted(Comparator.comparing(d -> d.item().getDueDate()))
                .toList();
    }

    /** Not completed and the due date has passed. */
    public List<Deadline> getOverdue() {
        LocalDate now = today.get();
        return all(null).stream()
                .filter(d -> d.item().isOverdue(now))
                .sorted(Comparator.comparing(d -> d.item().getDueDate()))
                .toList();
    }

    /** Upcoming items due within dueSoonDays. Used for alerts. */
    public List<Deadline> getDueSoon() {
        LocalDate limit = today.get().plusDays(dueSoonDays);
        return getUpcoming(null).stream()
                .filter(d -> !d.item().getDueDate().isAfter(limit))
                .toList();
    }

    /** Items imported without a due date. */
    public List<Deadline> getUndated() {
        return all(null).stream()
                .filter(d -> d.item().getDueDate() == null && !d.item().isCompleted())
                .toList();
    }

    public void markComplete(String itemId) {
        Deadline d = all(null).stream()
                .filter(x -> x.item().getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No assessment with id " + itemId));
        d.item().setCompleted(true);
        DataStore.getInstance().saveAll();
    }

    private List<Deadline> all(String courseCodeFilter) {
        List<Deadline> result = new ArrayList<>();
        for (Course c : DataStore.getInstance().getCourses()) {
            if (courseCodeFilter != null && !c.getCode().equalsIgnoreCase(courseCodeFilter.trim())) continue;
            for (AssessmentItem item : c.getItems()) {
                result.add(new Deadline(c, item));
            }
        }
        return result;
    }
}