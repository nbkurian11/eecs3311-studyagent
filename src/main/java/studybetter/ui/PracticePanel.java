package studybetter.ui;

import studybetter.controller.AgentController;
import studybetter.controller.AgentController.PastTestResult;
import studybetter.controller.AgentController.PracticeResult;
import studybetter.domain.AssessmentItem;
import studybetter.domain.ConceptMastery;
import studybetter.domain.GradeResult;
import studybetter.domain.MockExam;
import studybetter.domain.PracticeAssessment;
import studybetter.domain.PracticeSettings;
import studybetter.domain.Question;
import studybetter.domain.Topic;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Practice tab (F06, F07, F08): set up a quiz or mock exam, generate it with the agent (in the background),
 * answer the questions in the window, submit for automatic grading, and see the score, feedback and updated
 * mastery. The right side lists past attempts, which can be viewed or graded later (e.g. pending short answers),
 * and analyzes a graded past test (added to mastery only after the student confirms).
 * Mock exams show a countdown and submit automatically when time runs out.
 */
class PracticePanel extends TabPanel {

    private static final String QUIZ = "Quiz";
    private static final String MOCK_EXAM = "Mock exam";

    // settings
    private final JComboBox<String> courseBox = new JComboBox<>();
    private final JComboBox<String> typeBox = new JComboBox<>(new String[]{QUIZ, MOCK_EXAM});
    private final DefaultListModel<String> topicModel = new DefaultListModel<>();
    private final JList<String> topicList = new JList<>(topicModel);
    private final JTextField extraTopics = new JTextField(18);
    private final JCheckBox weakTopics = new JCheckBox("Use my weak topics");
    private final JSpinner count = new JSpinner(new SpinnerNumberModel(5, 1, PracticeAssessment.MAX_QUESTIONS, 1));
    private final JComboBox<String> difficulty = new JComboBox<>(PracticeSettings.DIFFICULTIES.toArray(new String[0]));
    private final JComboBox<String> targetExam = new JComboBox<>();
    private final JTextField maxWeek = new JTextField(4);
    private final JSpinner timeLimit = new JSpinner(new SpinnerNumberModel(60, 1, 300, 5));

    // taking the practice
    private final QuestionsPanel questionsPanel = new QuestionsPanel();
    private final JLabel practiceHeader = new JLabel("Set up a quiz or mock exam above and click Generate.");
    private final JLabel timerLabel = new JLabel(" ");
    private final JButton submitButton;
    private PracticeAssessment current;
    private final List<Supplier<String>> answerFields = new ArrayList<>();
    private Timer countdown;
    private long deadlineMillis;

    // history
    private final DefaultListModel<PracticeAssessment> historyModel = new DefaultListModel<>();
    private final JList<PracticeAssessment> historyList = new JList<>(historyModel);

    PracticePanel(AgentController controller, MainWindow window) {
        super(controller, window);
        courseBox.addActionListener(e -> window.attempt(this::loadCourse));
        typeBox.addActionListener(e -> updateTypeFields());
        weakTopics.addActionListener(e -> updateTypeFields());
        difficulty.setSelectedItem("medium");
        topicList.setVisibleRowCount(4);
        submitButton = button("Submit answers", () -> submit(false));
        submitButton.setEnabled(false);
        practiceHeader.setFont(practiceHeader.getFont().deriveFont(Font.BOLD));

        add(settingsPanel(), BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(4, 4));
        JPanel head = new JPanel(new BorderLayout());
        head.add(practiceHeader, BorderLayout.CENTER);
        head.add(timerLabel, BorderLayout.EAST);
        center.add(head, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(questionsPanel);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        center.add(scroll, BorderLayout.CENTER);
        center.add(row(submitButton), BorderLayout.SOUTH);

        historyList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof PracticeAssessment p) {
                    String status = p.getResult() == null ? "not graded"
                            : p.getResult().toString();
                    setText("<html>" + p.getSubmittedAt().toLocalDate() + " " + p.getTitle()
                            + "<br><small>" + p.getQuestions().size() + " questions, " + status + "</small></html>");
                }
                return this;
            }
        });
        JPanel history = new JPanel(new BorderLayout(4, 4));
        history.setBorder(BorderFactory.createTitledBorder("Past attempts"));
        history.add(new JScrollPane(historyList), BorderLayout.CENTER);
        JPanel historyButtons = new JPanel(new GridLayout(0, 2, 4, 4));
        historyButtons.add(button("View result", this::viewSelected));
        historyButtons.add(button("Grade selected", this::gradeSelected));
        historyButtons.add(button("Analyze past test...", this::analyzePastTest));
        history.add(historyButtons, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, history);
        split.setResizeWeight(0.72);
        add(split, BorderLayout.CENTER);
        updateTypeFields();
    }

    private JPanel settingsPanel() {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBorder(BorderFactory.createTitledBorder("New practice"));
        JPanel left = new JPanel(new GridLayout(0, 1));
        left.add(row(new JLabel("Course:"), courseBox, new JLabel("Type:"), typeBox, new JLabel("Questions:"), count,
                new JLabel("Difficulty:"), difficulty));
        left.add(row(new JLabel("Quiz:"), weakTopics, new JLabel("Other topics (comma-separated):"), extraTopics));
        left.add(row(new JLabel("Mock exam:"), new JLabel("Exam to simulate:"), targetExam,
                new JLabel("Up to week:"), maxWeek, new JLabel("Time limit (min):"), timeLimit));
        p.add(left, BorderLayout.CENTER);

        JPanel topics = new JPanel(new BorderLayout());
        topics.add(new JLabel("Quiz topics (Ctrl+click for several):"), BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(topicList);
        scroll.setPreferredSize(new Dimension(230, 80));
        topics.add(scroll, BorderLayout.CENTER);
        topics.add(button("Generate", this::generate), BorderLayout.SOUTH);
        p.add(topics, BorderLayout.EAST);
        return p;
    }

    @Override
    void refresh() {
        fillCourses(courseBox, false);
        loadCourse();
    }

    /** Topics, exams and past attempts of the selected course. */
    private void loadCourse() {
        topicModel.clear();
        targetExam.removeAllItems();
        historyModel.clear();
        if (courseBox.getSelectedItem() == null) return;
        String code = selectedCourse(courseBox);
        for (Topic t : controller.getTopics(code)) topicModel.addElement(t.getName());
        targetExam.addItem("(general mock exam)");
        for (AssessmentItem it : controller.findCourse(code).getItems()) targetExam.addItem(it.getTitle());
        controller.getPractices(code).forEach(historyModel::addElement);
        List<String> weak = controller.getWeakTopics(code);
        weakTopics.setToolTipText(weak.isEmpty() ? "No weak topics yet" : String.join(", ", weak));
    }

    /** Enables only the fields that matter for the chosen type, and sets a sensible question count. */
    private void updateTypeFields() {
        boolean quiz = QUIZ.equals(typeBox.getSelectedItem());
        topicList.setEnabled(quiz && !weakTopics.isSelected());
        extraTopics.setEnabled(quiz && !weakTopics.isSelected());
        weakTopics.setEnabled(quiz);
        targetExam.setEnabled(!quiz);
        maxWeek.setEnabled(!quiz);
        timeLimit.setEnabled(!quiz);
        int n = (Integer) count.getValue();
        if (!quiz && n == 5) count.setValue(10);
        if (quiz && n == 10) count.setValue(5);
    }

    // ---------- generate ----------

    private void generate() {
        if (current != null && !window.confirm("Discard the practice you are taking now?")) return;
        stopTimer();
        String code = selectedCourse(courseBox);
        boolean quiz = QUIZ.equals(typeBox.getSelectedItem());
        PracticeSettings settings = new PracticeSettings(code)
                .setNumQuestions((Integer) count.getValue())
                .setDifficulty((String) difficulty.getSelectedItem());
        if (quiz) {
            if (weakTopics.isSelected()) {
                settings.setUseWeakTopics(true);
            } else {
                List<String> topics = new ArrayList<>(topicList.getSelectedValuesList());
                Arrays.stream(extraTopics.getText().split(",")).map(String::trim)
                        .filter(s -> !s.isEmpty()).forEach(topics::add);
                settings.setTopics(topics);
            }
        } else {
            if (targetExam.getSelectedIndex() > 0) settings.setTargetExam((String) targetExam.getSelectedItem());
            if (!maxWeek.getText().isBlank()) settings.setMaxWeek(parseWeek(maxWeek.getText()));
            settings.setTimeLimitMinutes((Integer) timeLimit.getValue());
        }
        String type = quiz ? "quiz" : "mock-exam";
        clearPractice("Generating... (a mock exam can take a minute)");
        window.runInBackground("generating the " + (quiz ? "quiz" : "mock exam"),
                () -> controller.createPractice(type, settings),
                (PracticeResult result) -> {
                    if (!result.created()) {
                        clearPractice("Could not generate it.");
                        window.showError(new IllegalStateException("Could not generate the practice ("
                                + result.error() + "). Try again later."));
                        return;
                    }
                    startPractice(result.practice());
                });
    }

    private static int parseWeek(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Week must be a whole number");
        }
    }

    // ---------- answer ----------

    /** Shows the questions (radio buttons for multiple choice, a text box for short answers). */
    private void startPractice(PracticeAssessment practice) {
        StringBuilder notes = new StringBuilder();
        if (practice instanceof MockExam exam) {
            notes.append("Questions per topic:\n");
            for (Map.Entry<String, Integer> e : exam.getQuestionsPerTopic().entrySet()) {
                notes.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
        }
        practice.getWarnings().forEach(w -> notes.append("! ").append(w).append('\n'));
        if (practice instanceof MockExam exam && exam.isPartial()
                && !window.confirm(notes + "\nTake the shorter exam with " + exam.getQuestions().size() + " questions?")) {
            clearPractice("Cancelled.");
            return;
        }
        if (practice instanceof MockExam && !window.confirm(notes + "\nStart the "
                + practice.getTimeLimitMinutes() + "-minute timer now?")) {
            clearPractice("Cancelled.");
            return;
        }
        if (!(practice instanceof MockExam) && !notes.isEmpty()) window.info(notes.toString());

        clearPractice(practice.getTitle() + " (" + practice.getQuestions().size() + " questions)");
        current = practice;
        List<Question> questions = practice.getQuestions();
        for (int i = 0; i < questions.size(); i++) questionsPanel.add(questionCard(i, questions.get(i)));
        questionsPanel.revalidate();
        questionsPanel.repaint();
        submitButton.setEnabled(true);
        if (practice.getTimeLimitMinutes() > 0) startTimer(practice.getTimeLimitMinutes());
    }

    private JPanel questionCard(int index, Question q) {
        JPanel card = new JPanel(new BorderLayout(4, 4));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Q" + (index + 1) + "  [" + q.getTopic() + ", "
                        + (q.isMultipleChoice() ? "multiple choice" : "short answer") + "]"),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        JTextArea text = textArea();
        text.setText(q.getText());
        text.setOpaque(false);
        card.add(text, BorderLayout.NORTH);

        if (q.isMultipleChoice()) {
            JPanel options = new JPanel(new GridLayout(0, 1));
            ButtonGroup group = new ButtonGroup();
            List<JRadioButton> buttons = new ArrayList<>();
            for (int o = 0; o < q.getOptions().size(); o++) {
                JRadioButton b = new JRadioButton("<html>" + Question.letter(o) + ") " + escape(q.getOptions().get(o)) + "</html>");
                group.add(b);
                buttons.add(b);
                options.add(b);
            }
            card.add(options, BorderLayout.CENTER);
            answerFields.add(() -> {
                for (int o = 0; o < buttons.size(); o++) if (buttons.get(o).isSelected()) return Question.letter(o);
                return "";
            });
        } else {
            JTextArea answer = new JTextArea(3, 40);
            answer.setLineWrap(true);
            answer.setWrapStyleWord(true);
            card.add(new JScrollPane(answer), BorderLayout.CENTER);
            answerFields.add(answer::getText);
        }
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return card;
    }

    // ---------- submit and grade ----------

    /** Saves the answers, then grades them in the background (short answers need the LLM). */
    private void submit(boolean timeUp) {
        if (current == null) return;
        List<String> answers = answerFields.stream().map(Supplier::get).toList();
        long answered = answers.stream().filter(a -> !a.isBlank()).count();
        if (!timeUp && answered < answers.size()
                && !window.confirm("You answered " + answered + " of " + answers.size() + " questions. Submit anyway?")) {
            return;
        }
        stopTimer();
        PracticeAssessment practice = current;
        controller.saveAnswers(practice, answers);
        current = null;
        submitButton.setEnabled(false);
        loadCourse();
        grade(practice);
    }

    private void grade(PracticeAssessment practice) {
        practiceHeader.setText("Grading " + practice.getTitle() + "...");
        window.runInBackground("grading", () -> controller.gradePractice(practice.getId()),
                (GradeResult result) -> {
                    showReport(practice.getTitle(), practice.getCourseCode(), result);
                    loadCourse();
                });
    }

    private void viewSelected() {
        PracticeAssessment p = selectedAttempt();
        if (p.getResult() == null) throw new IllegalStateException("This attempt is not graded yet. Click 'Grade selected'.");
        showReport(p.getTitle(), p.getCourseCode(), p.getResult());
    }

    private void gradeSelected() {
        PracticeAssessment p = selectedAttempt();
        if (!p.needsGrading()) throw new IllegalStateException("This attempt is already fully graded.");
        if (current != null && !window.confirm("Leave the practice you are taking now?")) return;
        stopTimer();
        current = null;
        submitButton.setEnabled(false);
        grade(p);
    }

    private PracticeAssessment selectedAttempt() {
        PracticeAssessment p = historyList.getSelectedValue();
        if (p == null) throw new IllegalStateException("Select an attempt in 'Past attempts' first.");
        return p;
    }

    /** Score, per-question feedback, per-topic breakdown and the updated mastery of those topics. */
    private void showReport(String title, String courseCode, GradeResult result) {
        StringBuilder sb = new StringBuilder(resultText(result));
        List<String> topics = result.getTopicScores().stream().map(GradeResult.TopicScore::topic).toList();
        List<ConceptMastery> mastery = controller.getMastery(courseCode).stream()
                .filter(m -> topics.stream().anyMatch(t -> t.equalsIgnoreCase(m.getTopic())))
                .toList();
        if (!mastery.isEmpty()) {
            sb.append("\nYour mastery now:\n");
            mastery.forEach(m -> sb.append("  ").append(m).append('\n'));
        }
        showText(title + ": " + result, sb.toString());
    }

    /** Score, per-question verdicts, answers and feedback, per-topic breakdown and warnings. */
    private static String resultText(GradeResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Score: ").append(result).append("\n");
        List<GradeResult.QuestionResult> rows = result.getResults();
        for (int i = 0; i < rows.size(); i++) {
            GradeResult.QuestionResult r = rows.get(i);
            String verdict = r.pending() ? "PENDING" : r.correct() ? "Correct" : r.points() > 0 ? "Partly correct" : "Wrong";
            sb.append("\n").append(i + 1).append(". [").append(r.topic() == null ? "no topic" : r.topic()).append("] ")
                    .append(verdict);
            if (!r.pending()) {
                sb.append(" (").append(GradeResult.formatPoints(r.points())).append('/')
                        .append(GradeResult.formatPoints(r.maxPoints())).append(')');
            }
            sb.append('\n');
            if (r.question() != null) sb.append("   ").append(r.question()).append('\n');
            if (r.studentAnswer() != null) {
                sb.append("   Your answer: ").append(r.studentAnswer().isBlank() ? "(blank)" : r.studentAnswer()).append('\n');
            }
            if (r.correctAnswer() != null && !r.correct()) sb.append("   Correct answer: ").append(r.correctAnswer()).append('\n');
            if (r.feedback() != null && !r.feedback().isBlank()) sb.append("   ").append(r.feedback()).append('\n');
        }
        if (!result.getTopicScores().isEmpty()) {
            sb.append("\nBy topic:\n");
            for (GradeResult.TopicScore t : result.getTopicScores()) {
                sb.append(String.format("  %s: %s/%s (%.0f%%)%n", t.topic(), GradeResult.formatPoints(t.points()),
                        GradeResult.formatPoints(t.maxPoints()), t.percent()));
            }
        }
        result.getWarnings().forEach(w -> sb.append("! ").append(w).append('\n'));
        return sb.toString();
    }

    /** Replaces the question area with a read-only report. */
    private void showText(String header, String text) {
        clearPractice(header);
        JTextArea report = textArea();
        report.setText(text);
        report.setFont(new Font(Font.MONOSPACED, Font.PLAIN, report.getFont().getSize()));
        report.setAlignmentX(Component.LEFT_ALIGNMENT);
        questionsPanel.add(report);
        questionsPanel.revalidate();
        questionsPanel.repaint();
        report.setCaretPosition(0);
    }

    /**
     * F08 for a graded past test: the agent extracts questions, marks and topics and explains lost marks.
     * The result is shown for review and only added to mastery if the student confirms it is right (UC08).
     */
    private void analyzePastTest() {
        String code = selectedCourse(courseBox);
        if (current != null && !window.confirm("Leave the practice you are taking now?")) return;
        File file = chooseTextFile("Choose a graded past test (.txt or .md) for " + code);
        if (file == null) return;
        stopTimer();
        clearPractice("Analyzing " + file.getName() + "...");
        window.runInBackground("reading the test and explaining lost marks",
                () -> controller.analyzePastTest(code, file.getPath()),
                (PastTestResult analysis) -> {
                    if (!analysis.analyzed()) {
                        clearPractice("Could not analyze the test.");
                        window.showError(new IllegalStateException("Could not analyze the test ("
                                + analysis.error() + "). Try again later."));
                        return;
                    }
                    GradeResult result = analysis.result();
                    String title = result.getTitle() == null ? file.getName() : result.getTitle();
                    showText(title + ": " + result + " (review it)", resultText(result));
                    if (window.confirm("Are these questions and marks right?\nAdd them to your mastery for "
                            + code + "?")) {
                        controller.recordPastTest(result);
                        showReport(title, code, result);
                    } else {
                        window.setStatus("Past test not added to mastery");
                    }
                });
    }

    // ---------- timer (mock exams) ----------

    private void startTimer(int minutes) {
        deadlineMillis = System.currentTimeMillis() + minutes * 60_000L;
        countdown = new Timer(1000, e -> tick());
        tick();
        countdown.start();
    }

    private void tick() {
        long left = Math.max(0, deadlineMillis - System.currentTimeMillis()) / 1000;
        timerLabel.setText(String.format("Time left: %d:%02d  ", left / 60, left % 60));
        if (left == 0) {
            stopTimer();
            window.info("Time is up! Your answers are being submitted.");
            window.attempt(() -> submit(true));
        }
    }

    private void stopTimer() {
        if (countdown != null) countdown.stop();
        countdown = null;
        timerLabel.setText(" ");
    }

    private void clearPractice(String header) {
        current = null;
        answerFields.clear();
        submitButton.setEnabled(false);
        questionsPanel.removeAll();
        questionsPanel.revalidate();
        questionsPanel.repaint();
        practiceHeader.setText(header);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** A vertical list that always fits the scroll pane's width, so long question text wraps instead of scrolling. */
    private static class QuestionsPanel extends JPanel implements Scrollable {
        QuestionsPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
