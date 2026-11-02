package studybetter;

public class Main {
    public static void main(String[] args) {
        var store = studybetter.persistence.DataStore.getInstance();
        store.getSessions().clear();
        var courses = new studybetter.service.CourseManager();
        for (var c : new java.util.ArrayList<>(courses.getAllCourses())) courses.deleteCourse(c.getId());
        var c = courses.addCourse("EECS3311", "Software Design", "F26");

        // fake clock so the test is predictable
        var now = new java.util.concurrent.atomic.AtomicReference<>(java.time.LocalDateTime.of(2026, 11, 2, 10, 0));
        var logger = new studybetter.service.SessionLogger(now::get);
        logger.attach(e -> System.out.println("  [observer notified: " + e + "]"));

        logger.start(c.getId(), "Observer pattern");
        now.set(now.get().plusMinutes(90));
        System.out.println("Finished: " + logger.end());

        try {
            logger.addPastSession(c.getId(), "UML", now.get(), now.get().minusHours(1));
        } catch (IllegalArgumentException e) {
            System.out.println("Rejected: " + e.getMessage());
        }

        var longOne = logger.addPastSession(c.getId(), "Marathon", now.get(), now.get().plusHours(7));
        System.out.println("7h session needs confirmation? " + logger.needsConfirmation(longOne));

        logger.start(c.getId(), "Left running");
        var restored = new studybetter.service.SessionLogger(now::get);
        System.out.println("Restored after restart: " + restored.getActiveSession().map(Object::toString).orElse("none"));

        System.out.println("Total minutes logged: " + logger.totalMinutes(c.getId()));
    }
}