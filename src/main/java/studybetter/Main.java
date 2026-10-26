package studybetter;

public class Main {
    public static void main(String[] args) {
        var manager = new studybetter.service.CourseManager();
        for (var c : new java.util.ArrayList<>(manager.getAllCourses())) manager.deleteCourse(c.getId());

        var c1 = manager.addCourse("eecs3311", "Software Design", "F26");
        manager.addCourse("EECS3101", "Algorithms", "F26");
        System.out.println("Courses: " + manager.getAllCourses());

        try {
            manager.addCourse("EECS3311", "Duplicate", "F26");
        } catch (IllegalArgumentException e) {
            System.out.println("Rejected duplicate: " + e.getMessage());
        }

        manager.editCourse(c1.getId(), "Software Design (edited)", null);
        manager.addMaterial(c1.getId(), new studybetter.domain.Material("Week 1 slides", "notes/week1.pdf", "pdf"));
        System.out.println("After edit: " + manager.getCourse(c1.getId())
                + " with " + manager.getCourse(c1.getId()).getMaterials().size() + " material(s)");

        manager.deleteCourse(c1.getId());
        System.out.println("After delete: " + manager.getAllCourses());
    }
}