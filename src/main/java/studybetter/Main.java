package studybetter;

public class Main {
    public static void main(String[] args) {
        var courses = new studybetter.service.CourseManager();
        for (var c : new java.util.ArrayList<>(courses.getAllCourses())) courses.deleteCourse(c.getId());

        var c = courses.addCourse("EECS3311", "Software Design", "F26");
        var lab = new studybetter.domain.AssessmentItem("Labs", null, 20);
        var midterm = new studybetter.domain.AssessmentItem("Midterm", null, 20);
        c.addItem(lab);
        c.addItem(midterm);
        c.addItem(new studybetter.domain.AssessmentItem("Project", null, 30));
        c.addItem(new studybetter.domain.AssessmentItem("Final", null, 30));

        var grades = new studybetter.service.GradeBook();
        grades.attach(event -> System.out.println("  [observer notified: " + event + "]"));

        grades.enterGrade(lab.getId(), 16, 20);       // 80%
        grades.enterGrade(midterm.getId(), 45, 50);   // 90%
        System.out.printf("Current grade: %.1f%%%n", grades.currentGrade(c.getId()).getAsDouble());
        System.out.println("Target 80%: " + grades.requiredScore(c.getId(), 80));
        System.out.println("Target 99%: " + grades.requiredScore(c.getId(), 99));

        try {
            grades.enterGrade(lab.getId(), 25, 20);
        } catch (IllegalArgumentException e) {
            System.out.println("Rejected: " + e.getMessage());
        }
    }
}