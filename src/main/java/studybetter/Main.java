package studybetter;

public class Main {
    public static void main(String[] args) {
        var store = studybetter.persistence.DataStore.getInstance();
        store.getMastery().clear();
        var courses = new studybetter.service.CourseManager();
        for (var c : new java.util.ArrayList<>(courses.getAllCourses())) courses.deleteCourse(c.getId());
        var c = courses.addCourse("EECS3311", "Software Design", "F26");

        var mastery = new studybetter.service.MasteryTracker();
        mastery.attach(e -> System.out.println("  [observer notified: " + e + "]"));

        // quiz 1
        mastery.update(new studybetter.domain.GradeResult(c.getId(), java.util.List.of(
                new studybetter.domain.GradeResult.QuestionResult("Observer", true, "Correct"),
                new studybetter.domain.GradeResult.QuestionResult("Singleton", false, "Constructor must be private"),
                new studybetter.domain.GradeResult.QuestionResult("Adapter", true, "Correct"),
                new studybetter.domain.GradeResult.QuestionResult(null, true, "Untagged question"))));

        // quiz 2
        mastery.update(new studybetter.domain.GradeResult(c.getId(), java.util.List.of(
                new studybetter.domain.GradeResult.QuestionResult("Observer", true, "Correct"),
                new studybetter.domain.GradeResult.QuestionResult("Singleton", false, "Missing static getInstance"),
                new studybetter.domain.GradeResult.QuestionResult("Adapter", false, "Mixed up target and adaptee"))));

        System.out.println("Mastery (weakest first):");
        mastery.getMastery(c.getId()).forEach(m -> System.out.println("  " + m));
        System.out.println("Weak concepts: " + mastery.getWeakConcepts(c.getId()).stream()
                .map(studybetter.domain.ConceptMastery::getTopic).toList());
    }
}