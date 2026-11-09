package studybetter.ui;

import studybetter.controller.AgentController;
import studybetter.controller.AgentController.DeadlineReport;
import studybetter.domain.AssessmentItem;
import studybetter.domain.ConceptMastery;
import studybetter.domain.Course;
import studybetter.domain.StudySession;
import studybetter.service.DeadlineTracker.Deadline;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Scanner;

/**
 * Command-line interface for StudyBetter.
 * Reads one command per line, asks for each input with a prompt, and calls only
 * {@link AgentController} (the Facade), so the CLI never touches services or the DataStore directly.
 * Validation errors from the services are caught here and printed as "Error: ...".
 */
public class CLIApp {

    private final AgentController controller;
    private final Scanner in;

    public CLIApp(AgentController controller) {
        this.controller = controller;
        this.in = new Scanner(System.in);
    }

    /** Main loop: read a command, run it, repeat until "quit" or end of input. */
    public void run() {
        System.out.println("StudyBetter CLI. Type 'help' for commands.");
        controller.getActiveSession().ifPresent(s ->
                System.out.println("Note: a study session is still running (" + s.getTopic() + ")."));
        while (true) {
            String command = prompt("\n> ");
            if (command == null || command.equalsIgnoreCase("quit")) break;
            if (command.isEmpty()) continue;
            try {
                if (!dispatch(command.toLowerCase())) {
                    System.out.println("Unknown command '" + command + "'. Type 'help' for the list.");
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
        System.out.println("Goodbye.");
    }

    /** Runs one command. Returns false if the command is unknown. */
    private boolean dispatch(String command) {
        switch (command) {
            case "help" -> help();
            case "courses" -> listCourses();
            case "add-course" -> addCourse();
            case "delete-course" -> deleteCourse();
            case "add-item" -> addItem();
            case "deadlines" -> deadlines();
            case "complete" -> complete();
            case "grade" -> grade();
            case "grade-status" -> gradeStatus();
            case "start" -> startSession();
            case "end" -> endSession();
            case "mastery" -> mastery();
            default -> { return false; }
        }
        return true;
    }

    private void help() {
        System.out.println("""
                Commands:
                  help           show this list
                  courses        list your courses
                  add-course     add a course (code, name, term)
                  delete-course  delete a course and its assessments
                  add-item       add an assessment to a course (title, due date, weight)
                  deadlines      show upcoming, due-soon, overdue and undated assessments
                  complete       mark an assessment as completed
                  grade          enter a mark for an assessment
                  grade-status   show current grade and the score needed for a target
                  start          start a study session
                  end            end the running study session
                  mastery        show concept mastery for a course (weakest first)
                  quit           exit""");
    }

    // ---------- courses ----------

    private void listCourses() {
        List<Course> courses = controller.getCourses();
        if (courses.isEmpty()) {
            System.out.println("No courses yet. Use 'add-course'.");
            return;
        }
        for (Course c : courses) {
            System.out.println("  " + c + " | " + c.getItems().size() + " assessment(s)");
        }
    }

    private void addCourse() {
        String code = required("Course code: ");
        String name = required("Course name: ");
        String term = required("Term (e.g. F26): ");
        Course c = controller.addCourse(code, name, term);
        System.out.println("Added " + c);
    }

    private void deleteCourse() {
        Course c = controller.findCourse(required("Course code: "));
        String answer = prompt("Delete " + c + " and all its assessments? (y/n): ");
        if (answer != null && answer.equalsIgnoreCase("y")) {
            controller.deleteCourse(c.getCode());
            System.out.println("Deleted " + c.getCode());
        } else {
            System.out.println("Cancelled.");
        }
    }

    // ---------- assessments and deadlines ----------

    private void addItem() {
        String code = controller.findCourse(required("Course code: ")).getCode();
        String title = required("Title: ");
        LocalDate due = parseDate(prompt("Due date (YYYY-MM-DD, blank for none): "));
        double weight = parseNumber(required("Weight (%): "));
        AssessmentItem item = controller.addAssessment(code, title, due, weight);
        System.out.println("Added " + item.getTitle() + " to " + code);
    }

    private void deadlines() {
        String filter = prompt("Course code (blank for all): ");
        DeadlineReport r = controller.getDeadlines(filter == null || filter.isBlank() ? null : filter);
        printSection("Upcoming", r.upcoming());
        printSection("Due soon (next 7 days)", r.dueSoon());
        printSection("Overdue", r.overdue());
        printSection("No due date", r.undated());
    }

    private void printSection(String heading, List<Deadline> deadlines) {
        System.out.println(heading + ":");
        if (deadlines.isEmpty()) System.out.println("  (none)");
        deadlines.forEach(d -> System.out.println("  " + d));
    }

    private void complete() {
        AssessmentItem item = pickAssessment(controller.findCourse(required("Course code: ")));
        if (item == null) return;
        controller.markComplete(item.getId());
        System.out.println("Marked '" + item.getTitle() + "' as completed.");
    }

    // ---------- grades ----------

    private void grade() {
        AssessmentItem item = pickAssessment(controller.findCourse(required("Course code: ")));
        if (item == null) return;
        double score = parseNumber(required("Score: "));
        double outOf = parseNumber(required("Out of: "));
        controller.enterGrade(item.getId(), score, outOf);
        System.out.printf("Saved %.1f/%.1f for '%s'.%n", score, outOf, item.getTitle());
    }

    private void gradeStatus() {
        String code = controller.findCourse(required("Course code: ")).getCode();
        OptionalDouble current = controller.currentGrade(code);
        if (current.isPresent()) {
            System.out.printf("Current grade (graded work only): %.1f%%%n", current.getAsDouble());
        } else {
            System.out.println("No graded assessments yet.");
        }
        String target = prompt("Target final grade % (blank to skip): ");
        if (target != null && !target.isBlank()) {
            System.out.println(controller.requiredScore(code, parseNumber(target)));
        }
    }

    // ---------- study sessions ----------

    private void startSession() {
        String code = controller.findCourse(required("Course code: ")).getCode();
        String topic = required("Topic: ");
        StudySession s = controller.startSession(code, topic);
        System.out.println("Session started at " + s.getStart().toLocalTime().withNano(0)
                + ". Type 'end' when you finish.");
    }

    private void endSession() {
        StudySession s = controller.endSession();
        System.out.println("Session ended: " + s);
        if (controller.sessionNeedsConfirmation(s)) {
            System.out.println("Warning: this session is unusually long. Did you forget to end it?");
        }
    }

    // ---------- mastery ----------

    private void mastery() {
        String code = controller.findCourse(required("Course code: ")).getCode();
        List<ConceptMastery> list = controller.getMastery(code);
        if (list.isEmpty()) {
            System.out.println("No mastery data yet for " + code + ".");
            return;
        }
        System.out.println("Mastery for " + code + " (weakest first):");
        list.forEach(m -> System.out.println("  " + m));
    }

    // ---------- input helpers ----------

    /** Shows the course's assessments as a numbered list and returns the one picked, or null if none. */
    private AssessmentItem pickAssessment(Course course) {
        List<AssessmentItem> items = course.getItems();
        if (items.isEmpty()) {
            System.out.println(course.getCode() + " has no assessments. Use 'add-item' first.");
            return null;
        }
        for (int i = 0; i < items.size(); i++) {
            AssessmentItem it = items.get(i);
            String due = it.getDueDate() == null ? "no date" : "due " + it.getDueDate();
            String status = it.isGraded() ? String.format(" | %.1f/%.1f", it.getScore(), it.getOutOf()) : "";
            if (it.isCompleted()) status += " | done";
            System.out.printf("  %d. %s | %s | %.1f%%%s%n", i + 1, it.getTitle(), due, it.getWeight(), status);
        }
        int n = (int) parseNumber(required("Pick a number: "));
        if (n < 1 || n > items.size()) {
            throw new IllegalArgumentException("Pick a number from 1 to " + items.size());
        }
        return items.get(n - 1);
    }

    /** Prints a prompt and returns the trimmed line, or null at end of input. */
    private String prompt(String message) {
        System.out.print(message);
        if (!in.hasNextLine()) return null;
        return in.nextLine().trim();
    }

    /** Like prompt, but the answer cannot be blank. */
    private String required(String message) {
        String value = prompt(message);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A value is required");
        }
        return value;
    }

    private double parseNumber(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text + "' is not a number");
        }
    }

    private LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Date must be YYYY-MM-DD, got '" + text + "'");
        }
    }
}
