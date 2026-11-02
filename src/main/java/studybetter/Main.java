package studybetter;

public class Main {
    public static void main(String[] args) {
        var courses = new studybetter.service.CourseManager();
        for (var c : new java.util.ArrayList<>(courses.getAllCourses())) courses.deleteCourse(c.getId());

        var today = java.time.LocalDate.now();
        var c = courses.addCourse("EECS3311", "Software Design", "F26");
        c.addItem(new studybetter.domain.AssessmentItem("Lab 3", today.minusDays(2), 5));
        c.addItem(new studybetter.domain.AssessmentItem("Project Stage 2", today.plusDays(8), 15));
        c.addItem(new studybetter.domain.AssessmentItem("Quiz 2", today.plusDays(3), 5));
        c.addItem(new studybetter.domain.AssessmentItem("Participation", null, 5));
        studybetter.persistence.DataStore.getInstance().saveAll();

        var tracker = new studybetter.service.DeadlineTracker();
        System.out.println("Upcoming:");  tracker.getUpcoming(null).forEach(d -> System.out.println("  " + d));
        System.out.println("Due soon:");  tracker.getDueSoon().forEach(d -> System.out.println("  " + d));
        System.out.println("Overdue:");   tracker.getOverdue().forEach(d -> System.out.println("  " + d));
        System.out.println("No date:");   tracker.getUndated().forEach(d -> System.out.println("  " + d));

        var quiz = tracker.getUpcoming(null).get(0).item();
        tracker.markComplete(quiz.getId());
        System.out.println("After completing " + quiz.getTitle() + ": " + tracker.getUpcoming(null).size() + " upcoming");
    }
}