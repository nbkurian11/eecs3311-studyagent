package studybetter.ui;

import studybetter.controller.AgentController;
import studybetter.controller.AgentController.DeadlineReport;
import studybetter.controller.AgentController.PracticeResult;
import studybetter.controller.AgentController.SyllabusResult;
import studybetter.controller.AgentController.SyllabusSaveResult;
import studybetter.controller.AgentController.UploadResult;
import studybetter.domain.AssessmentItem;
import studybetter.domain.ConceptMastery;
import studybetter.domain.Course;
import studybetter.domain.Explanation;
import studybetter.domain.Material;
import studybetter.domain.MockExam;
import studybetter.domain.PracticeAssessment;
import studybetter.domain.PracticeSettings;
import studybetter.domain.Question;
import studybetter.domain.StudySession;
import studybetter.domain.SyllabusDraft;
import studybetter.domain.Topic;
import studybetter.service.DeadlineTracker.Deadline;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        if (!controller.isUsingRealLlm()) {
            System.out.println("Note: no GEMINI_API_KEY found in .env, so AI features use the offline mock.");
        }
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
            case "import-syllabus" -> importSyllabus();
            case "topics" -> topics();
            case "deadlines" -> deadlines();
            case "complete" -> complete();
            case "grade" -> grade();
            case "grade-status" -> gradeStatus();
            case "start" -> startSession();
            case "end" -> endSession();
            case "mastery" -> mastery();
            case "upload-material" -> uploadMaterial();
            case "materials" -> materials();
            case "summarize" -> summarizePending();
            case "explain" -> explain();
            case "quiz" -> quiz();
            case "mock-exam" -> mockExam();
            case "practices" -> practices();
            default -> { return false; }
        }
        return true;
    }

    private void help() {
        System.out.println("""
                Commands:
                  help             show this list
                  courses          list your courses
                  add-course       add a course (code, name, term)
                  delete-course    delete a course and its assessments
                  add-item         add an assessment to a course (title, due date, weight)
                  import-syllabus  extract assessments and topics from a .txt/.md syllabus (review before saving)
                  topics           list a course's topics by week
                  deadlines        show upcoming, due-soon, overdue and undated assessments
                  complete         mark an assessment as completed
                  grade            enter a mark for an assessment
                  grade-status     show current grade and the score needed for a target
                  start            start a study session
                  end              end the running study session
                  mastery          show concept mastery for a course (weakest first)
                  upload-material  add a .txt/.md file to a course and get an AI summary
                  materials        list a course's materials and their summaries
                  summarize        retry AI summaries that are still pending
                  explain          ask the AI to explain a concept using your course materials
                  quiz             generate a practice quiz on chosen or weak topics and answer it
                  mock-exam        generate a timed mock exam weighted by syllabus topics and answer it
                  practices        list your submitted quizzes and mock exams
                  quit             exit""");
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
        addItemTo(code, required("Title: "));
    }

    /** Asks for the due date and weight of one assessment and saves it. */
    private void addItemTo(String code, String title) {
        LocalDate due = parseDate(prompt("Due date (YYYY-MM-DD, blank for none): "));
        double weight = parseNumber(required("Weight (%): "));
        AssessmentItem item = controller.addAssessment(code, title, due, weight);
        System.out.println("Added " + item.getTitle() + " to " + code);
    }

    // ---------- syllabus import (agent) ----------

    /** F02: extract, show for review, save only after "y". Falls back to manual entry if the AI fails. */
    private void importSyllabus() {
        String code = controller.findCourse(required("Course code: ")).getCode();
        String path = stripQuotes(required("Path to syllabus (.txt or .md): "));
        System.out.println("Reading the syllabus...");
        SyllabusResult result = controller.importSyllabus(code, path);
        if (!result.extracted()) {
            System.out.println("Could not extract the syllabus automatically (" + result.error() + ").");
            manualEntry(code);
            return;
        }
        SyllabusDraft draft = result.draft();
        System.out.println("\nAssessments found:");
        if (draft.getItems().isEmpty()) System.out.println("  (none)");
        for (AssessmentItem it : draft.getItems()) {
            String due = it.getDueDate() == null ? "NO DATE" : it.getDueDate().toString();
            System.out.printf("  - %-28s %-10s %5.1f%%%n", it.getTitle(), due, it.getWeight());
        }
        System.out.printf("  Total weight: %.1f%%%n", draft.totalWeight());
        System.out.println("Topics found:");
        if (draft.getTopics().isEmpty()) System.out.println("  (none)");
        draft.getTopics().forEach(t -> System.out.println("  - " + t));
        if (!draft.getWarnings().isEmpty()) {
            System.out.println("Please check:");
            draft.getWarnings().forEach(w -> System.out.println("  ! " + w));
        }
        String answer = prompt("\nSave these to " + code + "? (y/n): ");
        if (answer == null || !answer.equalsIgnoreCase("y")) {
            System.out.println("Nothing saved. You can add items yourself with 'add-item'.");
            return;
        }
        SyllabusSaveResult saved = controller.saveSyllabus(code, draft);
        System.out.println("Saved " + saved.itemsAdded() + " assessment(s) and " + saved.topicsAdded()
                + " topic(s) to " + code + ".");
        if (!saved.skipped().isEmpty()) {
            System.out.println("Skipped (already in the course): " + String.join(", ", saved.skipped()));
        }
        if (!draft.itemsWithoutDates().isEmpty()) {
            System.out.println("Tip: items with no date can be completed and graded, but won't appear as deadlines.");
        }
    }

    /** Fallback for F02 when the AI is unavailable: enter assessments one by one, like 'add-item'. */
    private void manualEntry(String code) {
        String answer = prompt("Enter the assessments manually now? (y/n): ");
        if (answer == null || !answer.equalsIgnoreCase("y")) {
            System.out.println("OK. You can add them later with 'add-item'.");
            return;
        }
        int added = 0;
        while (true) {
            String title = prompt("\nTitle (blank to finish): ");
            if (title == null || title.isBlank()) break;
            try {
                addItemTo(code, title);
                added++;
            } catch (IllegalArgumentException e) {
                System.out.println("Error: " + e.getMessage() + " (item not added)");
            }
        }
        System.out.println("Added " + added + " assessment(s) to " + code + ".");
    }

    private void topics() {
        String code = controller.findCourse(required("Course code: ")).getCode();
        List<Topic> list = controller.getTopics(code);
        if (list.isEmpty()) {
            System.out.println(code + " has no topics. Use 'import-syllabus'.");
            return;
        }
        list.forEach(t -> System.out.println("  " + t));
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

    // ---------- materials and explanations (agent) ----------

    private void uploadMaterial() {
        String code = controller.findCourse(required("Course code: ")).getCode();
        String path = stripQuotes(required("Path to .txt or .md file: "));
        System.out.println("Reading and summarizing...");
        UploadResult result = controller.uploadMaterial(code, path);
        Material m = result.material();
        System.out.println("Saved '" + m.getTitle() + "' to " + code + " (" + m.getText().length() + " characters).");
        if (result.summarized()) {
            System.out.println("Summary:\n" + m.getSummary());
        } else {
            System.out.println("AI summary is pending (" + result.summaryError() + ").");
            System.out.println("Run 'summarize' later to try again.");
        }
    }

    private void materials() {
        String code = controller.findCourse(required("Course code: ")).getCode();
        List<Material> list = controller.getMaterials(code);
        if (list.isEmpty()) {
            System.out.println(code + " has no materials. Use 'upload-material'.");
            return;
        }
        for (Material m : list) {
            System.out.println("\n* " + m);
            if (m.getSummary() != null && !m.isSummaryPending()) System.out.println(m.getSummary());
        }
    }

    private void summarizePending() {
        String code = controller.findCourse(required("Course code: ")).getCode();
        long pending = controller.getMaterials(code).stream().filter(Material::isSummaryPending).count();
        if (pending == 0) {
            System.out.println("No pending summaries for " + code + ".");
            return;
        }
        System.out.println("Summarizing " + pending + " material(s)...");
        for (UploadResult r : controller.retryPendingSummaries(code)) {
            if (r.summarized()) {
                System.out.println("Summarized '" + r.material().getTitle() + "':\n" + r.material().getSummary());
            } else {
                System.out.println("Still pending '" + r.material().getTitle() + "': " + r.summaryError());
            }
        }
    }

    private void explain() {
        String code = controller.findCourse(required("Course code: ")).getCode();
        String question = required("What should I explain? ");
        System.out.println("Thinking...");
        Explanation e = controller.explainConcept(code, question);
        System.out.println("\n" + e.getText());
        if (!e.getSources().isEmpty()) {
            System.out.println("\nSources: " + String.join(", ", e.getSources()));
        }
    }

    // ---------- practice: quizzes and mock exams (agent) ----------

    /** F06: pick topics (or weak topics), count and difficulty; generate; answer; save for grading. */
    private void quiz() {
        String code = controller.findCourse(required("Course code: ")).getCode();
        List<Topic> topics = controller.getTopics(code);
        List<String> weak = controller.getWeakTopics(code);
        if (!topics.isEmpty()) {
            System.out.println("Topics in " + code + ":");
            for (int i = 0; i < topics.size(); i++) System.out.println("  " + (i + 1) + ". " + topics.get(i));
        }
        System.out.println("Weak topics: " + (weak.isEmpty() ? "(none yet)" : String.join(", ", weak)));
        String choice = required("Topics (numbers or names, comma-separated, or 'weak'): ");

        PracticeSettings settings = new PracticeSettings(code);
        if (choice.equalsIgnoreCase("weak")) {
            settings.setUseWeakTopics(true);
        } else {
            settings.setTopics(parseTopicChoice(choice, topics));
        }
        settings.setNumQuestions(intOrDefault(prompt("Number of questions [5]: "), 5));
        settings.setDifficulty(orDefault(prompt("Difficulty (easy/medium/hard) [medium]: "), "medium"));

        System.out.println("Generating the quiz...");
        PracticeResult result = controller.createPractice("quiz", settings);
        if (!result.created()) {
            System.out.println("Could not generate the quiz (" + result.error() + "). Try again later.");
            return;
        }
        PracticeAssessment quiz = result.practice();
        System.out.println("\n" + quiz.getTitle() + " (" + quiz.getQuestions().size() + " questions)");
        quiz.getWarnings().forEach(w -> System.out.println("  ! " + w));
        takePractice(quiz);
    }

    /** F07: pick the exam to simulate, week range, time limit; see the topic split; answer against the clock. */
    private void mockExam() {
        Course course = controller.findCourse(required("Course code: "));
        String code = course.getCode();
        if (controller.getTopics(code).isEmpty()) {
            System.out.println(code + " has no topics yet. Use 'import-syllabus' first.");
            return;
        }
        PracticeSettings settings = new PracticeSettings(code);
        List<AssessmentItem> items = course.getItems();
        if (!items.isEmpty()) {
            System.out.println("Exam to simulate:");
            for (int i = 0; i < items.size(); i++) System.out.println("  " + (i + 1) + ". " + items.get(i).getTitle());
            String pick = prompt("Pick a number (blank for a general mock exam): ");
            if (pick != null && !pick.isBlank()) {
                int n = (int) parseNumber(pick);
                if (n < 1 || n > items.size()) throw new IllegalArgumentException("Pick a number from 1 to " + items.size());
                settings.setTargetExam(items.get(n - 1).getTitle());
            }
        }
        String week = prompt("Cover topics up to week (blank for all): ");
        if (week != null && !week.isBlank()) settings.setMaxWeek(intOrDefault(week, 0));
        settings.setTimeLimitMinutes(intOrDefault(prompt("Time limit in minutes [60]: "), 60));
        settings.setNumQuestions(intOrDefault(prompt("Number of questions [10]: "), 10));
        settings.setDifficulty(orDefault(prompt("Difficulty (easy/medium/hard) [medium]: "), "medium"));

        System.out.println("Generating the mock exam (this can take a minute)...");
        PracticeResult result = controller.createPractice("mock-exam", settings);
        if (!result.created()) {
            System.out.println("Could not generate the mock exam (" + result.error() + "). Try again later.");
            return;
        }
        MockExam exam = (MockExam) result.practice();
        System.out.println("\n" + exam.getTitle() + ": " + exam.getQuestions().size() + " questions, "
                + exam.getTimeLimitMinutes() + " minutes");
        System.out.println("Questions per topic:");
        for (Map.Entry<String, Integer> e : exam.getQuestionsPerTopic().entrySet()) {
            System.out.println("  " + e.getKey() + ": " + e.getValue());
        }
        exam.getWarnings().forEach(w -> System.out.println("  ! " + w));
        if (exam.isPartial()) {
            String answer = prompt("Take the shorter exam with " + exam.getQuestions().size() + " questions? (y/n): ");
            if (answer == null || !answer.equalsIgnoreCase("y")) {
                System.out.println("Cancelled. Try 'mock-exam' again later.");
                return;
            }
        }
        prompt("Press Enter to start the timer...");
        takePractice(exam);
    }

    /**
     * Shows the questions one at a time and collects answers (letters for multiple choice, text for short answer;
     * blank skips). For a timed exam, an answer typed after time runs out is not counted and the answers
     * so far are submitted automatically. The answers are then saved for grading.
     */
    private void takePractice(PracticeAssessment practice) {
        List<Question> questions = practice.getQuestions();
        List<String> answers = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) answers.add("");
        Instant deadline = practice.getTimeLimitMinutes() > 0
                ? Instant.now().plus(Duration.ofMinutes(practice.getTimeLimitMinutes())) : null;

        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);
            String kind = q.isMultipleChoice() ? "multiple choice" : "short answer";
            String timeLeft = deadline == null ? "" : " | " + formatRemaining(deadline) + " left";
            System.out.println("\nQ" + (i + 1) + "/" + questions.size() + " [" + q.getTopic() + ", " + kind + "]"
                    + timeLeft);
            System.out.println(q.getText());
            for (int o = 0; o < q.getOptions().size(); o++) {
                System.out.println("  " + Question.letter(o) + ") " + q.getOptions().get(o));
            }
            String answer = readAnswer(q);
            if (deadline != null && Instant.now().isAfter(deadline)) {
                System.out.println("Time is up! That answer was not counted; your earlier answers are submitted.");
                break;
            }
            if (answer == null) {
                System.out.println("\nInput ended; submitting the answers so far.");
                break;
            }
            answers.set(i, answer);
        }
        controller.saveAnswers(practice, answers);
        System.out.println("\nSubmitted: " + practice.getAnsweredCount() + " of " + questions.size()
                + " questions answered. Your answers are saved for grading (see 'practices').");
    }

    /** Asks until the answer is valid. Returns "" for a skip, or null at end of input. */
    private String readAnswer(Question q) {
        if (!q.isMultipleChoice()) return prompt("Your answer (blank to skip): ");
        String last = Question.letter(q.getOptions().size() - 1);
        while (true) {
            String answer = prompt("Your answer (A-" + last + ", blank to skip): ");
            if (answer == null || answer.isBlank()) return answer;
            int index = Question.indexOfLetter(answer);
            if (index >= 0 && index < q.getOptions().size()) return answer.toUpperCase();
            System.out.println("Please type a letter from A to " + last + ".");
        }
    }

    private void practices() {
        String code = controller.findCourse(required("Course code: ")).getCode();
        List<PracticeAssessment> list = controller.getPractices(code);
        if (list.isEmpty()) {
            System.out.println("No submitted practice for " + code + " yet. Use 'quiz' or 'mock-exam'.");
            return;
        }
        for (PracticeAssessment p : list) {
            System.out.println("  " + p.getSubmittedAt().toLocalDate() + "  " + p);
        }
    }

    /** "1, 3" picks topics by number; anything else is taken as a topic name. */
    private List<String> parseTopicChoice(String choice, List<Topic> topics) {
        List<String> chosen = new ArrayList<>();
        for (String part : choice.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (p.matches("\\d+")) {
                int n = Integer.parseInt(p);
                if (n < 1 || n > topics.size()) {
                    throw new IllegalArgumentException("Topic number must be from 1 to " + topics.size());
                }
                chosen.add(topics.get(n - 1).getName());
            } else {
                chosen.add(p);
            }
        }
        return chosen;
    }

    private static String formatRemaining(Instant deadline) {
        long seconds = Math.max(0, Duration.between(Instant.now(), deadline).getSeconds());
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    // ---------- input helpers ----------

    private String orDefault(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text;
    }

    private int intOrDefault(String text, int fallback) {
        if (text == null || text.isBlank()) return fallback;
        double value = parseNumber(text);
        if (value != Math.floor(value)) throw new IllegalArgumentException("'" + text + "' is not a whole number");
        return (int) value;
    }

    /** Windows "Copy as path" wraps paths in quotes; remove them. */
    private String stripQuotes(String text) {
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

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
