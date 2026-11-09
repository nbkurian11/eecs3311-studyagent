package studybetter.ui;

import studybetter.controller.AgentController;
import studybetter.controller.AgentController.SyllabusResult;
import studybetter.controller.AgentController.SyllabusSaveResult;
import studybetter.controller.AgentController.UploadResult;
import studybetter.domain.AssessmentItem;
import studybetter.domain.Course;
import studybetter.domain.Material;
import studybetter.domain.SyllabusDraft;
import studybetter.domain.Topic;

import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Courses tab (F01, F02): add and delete courses, upload .txt/.md materials and read their AI summaries,
 * and import a syllabus, which is shown in a review dialog and saved only after the student confirms.
 */
class CoursesPanel extends TabPanel {

    private final DefaultListModel<Course> courseModel = new DefaultListModel<>();
    private final JList<Course> courseList = new JList<>(courseModel);
    private final JTextArea overview = textArea();
    private final DefaultListModel<Material> materialModel = new DefaultListModel<>();
    private final JList<Material> materialList = new JList<>(materialModel);
    private final JTextArea summary = textArea();

    CoursesPanel(AgentController controller, MainWindow window) {
        super(controller, window);
        courseList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        courseList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showCourse();
        });
        materialList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showMaterial();
        });

        JPanel left = new JPanel(new BorderLayout(4, 4));
        left.add(new JLabel("Courses"), BorderLayout.NORTH);
        JSplitPane leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(courseList), titled(new JScrollPane(overview), "Assessments and topics"));
        leftSplit.setResizeWeight(0.4);
        left.add(leftSplit, BorderLayout.CENTER);
        left.add(row(button("Add course", this::addCourse), button("Delete course", this::deleteCourse)),
                BorderLayout.SOUTH);

        JPanel right = new JPanel(new BorderLayout(4, 4));
        right.add(new JLabel("Materials of the selected course"), BorderLayout.NORTH);
        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(materialList), titled(new JScrollPane(summary), "Summary"));
        rightSplit.setResizeWeight(0.35);
        right.add(rightSplit, BorderLayout.CENTER);
        right.add(row(
                button("Upload material...", this::uploadMaterial),
                button("Retry pending summaries", this::retrySummaries),
                button("Import syllabus...", this::importSyllabus),
                button("Add assessment...", () -> addAssessment(selectedCode()))), BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.35);
        add(split, BorderLayout.CENTER);
    }

    @Override
    void refresh() {
        Course selected = courseList.getSelectedValue();
        courseModel.clear();
        for (Course c : controller.getCourses()) courseModel.addElement(c);
        if (selected != null) {
            for (int i = 0; i < courseModel.size(); i++) {
                if (courseModel.get(i).getId().equals(selected.getId())) courseList.setSelectedIndex(i);
            }
        }
        if (courseList.getSelectedIndex() < 0 && !courseModel.isEmpty()) courseList.setSelectedIndex(0);
        showCourse();
    }

    private void showCourse() {
        Course c = courseList.getSelectedValue();
        materialModel.clear();
        summary.setText("");
        if (c == null) {
            overview.setText("No courses yet. Click 'Add course'.");
            return;
        }
        c = controller.findCourse(c.getCode()); // latest data
        c.getMaterials().forEach(materialModel::addElement);
        StringBuilder sb = new StringBuilder(c + "\n\nAssessments:\n");
        if (c.getItems().isEmpty()) sb.append("  (none: import a syllabus or add them)\n");
        for (AssessmentItem it : c.getItems()) {
            sb.append(String.format("  - %s, %s, %.1f%%%s%n", it.getTitle(),
                    it.getDueDate() == null ? "no date" : "due " + it.getDueDate(), it.getWeight(),
                    it.isCompleted() ? " (done)" : ""));
        }
        sb.append("\nTopics:\n");
        if (c.getTopics().isEmpty()) sb.append("  (none: import a syllabus)\n");
        for (Topic t : c.getTopics()) sb.append("  - ").append(t).append('\n');
        overview.setText(sb.toString());
        overview.setCaretPosition(0);
    }

    private void showMaterial() {
        Material m = materialList.getSelectedValue();
        if (m == null) {
            summary.setText("");
        } else if (m.isSummaryPending()) {
            summary.setText("The AI summary is pending. Click 'Retry pending summaries' when the AI is available.");
        } else {
            summary.setText(m.getSummary() == null ? "(no summary)" : m.getSummary());
        }
        summary.setCaretPosition(0);
    }

    private String selectedCode() {
        Course c = courseList.getSelectedValue();
        if (c == null) throw new IllegalStateException("Select a course first (or add one).");
        return c.getCode();
    }

    private void addCourse() {
        JTextField code = new JTextField();
        JTextField name = new JTextField();
        JTextField term = new JTextField("Fall 2026");
        JPanel form = form("Code (e.g. EECS3311):", code, "Name:", name, "Term:", term);
        if (JOptionPane.showConfirmDialog(window, form, "Add course", JOptionPane.OK_CANCEL_OPTION)
                != JOptionPane.OK_OPTION) return;
        Course c = controller.addCourse(code.getText().trim(), name.getText().trim(), term.getText().trim());
        refresh();
        courseList.setSelectedValue(findInModel(c.getCode()), true);
        window.setStatus("Added " + c.getCode());
    }

    private void deleteCourse() {
        String code = selectedCode();
        if (!window.confirm("Delete " + code + " with all its materials, assessments and topics?")) return;
        controller.deleteCourse(code);
        refresh();
        window.setStatus("Deleted " + code);
    }

    private void uploadMaterial() {
        String code = selectedCode();
        File file = chooseTextFile("Choose a .txt or .md material for " + code);
        if (file == null) return;
        window.runInBackground("reading and summarizing " + file.getName(),
                () -> controller.uploadMaterial(code, file.getPath()),
                (UploadResult result) -> {
                    showCourse();
                    materialList.setSelectedValue(result.material(), true);
                    String saved = "Saved '" + result.material().getTitle() + "' to " + code + ".";
                    if (result.summarized()) window.setStatus(saved);
                    else window.info(saved + "\nThe AI summary is pending (" + result.summaryError() + ").");
                });
    }

    private void retrySummaries() {
        String code = selectedCode();
        long pending = controller.getMaterials(code).stream().filter(Material::isSummaryPending).count();
        if (pending == 0) {
            window.info("No pending summaries for " + code + ".");
            return;
        }
        window.runInBackground("summarizing " + pending + " material(s)",
                () -> controller.retryPendingSummaries(code),
                (List<UploadResult> results) -> {
                    showCourse();
                    StringBuilder sb = new StringBuilder();
                    for (UploadResult r : results) {
                        sb.append(r.summarized() ? "Summarized: " : "Still pending: ")
                                .append(r.material().getTitle())
                                .append(r.summarized() ? "" : " (" + r.summaryError() + ")").append('\n');
                    }
                    window.info(sb.toString());
                });
    }

    /** F02: extract with the agent, show the draft for review, save only after the student confirms. */
    private void importSyllabus() {
        String code = selectedCode();
        File file = chooseTextFile("Choose the syllabus (.txt or .md) for " + code);
        if (file == null) return;
        window.runInBackground("reading the syllabus",
                () -> controller.importSyllabus(code, file.getPath()),
                (SyllabusResult result) -> {
                    if (!result.extracted()) {
                        if (window.confirm("Could not extract the syllabus automatically (" + result.error()
                                + ").\nEnter the assessments manually now?")) {
                            addAssessment(code);
                        }
                        return;
                    }
                    if (!reviewSyllabus(code, result.draft())) {
                        window.setStatus("Syllabus not saved");
                        return;
                    }
                    SyllabusSaveResult saved = controller.saveSyllabus(code, result.draft());
                    showCourse();
                    String msg = "Saved " + saved.itemsAdded() + " assessment(s) and " + saved.topicsAdded()
                            + " topic(s) to " + code + ".";
                    if (!saved.skipped().isEmpty()) {
                        msg += "\nSkipped (already in the course): " + String.join(", ", saved.skipped());
                    }
                    window.info(msg);
                });
    }

    /** The review dialog: assessments, topics, total weight and warnings; returns true if the student confirms. */
    private boolean reviewSyllabus(String code, SyllabusDraft draft) {
        DefaultTableModel items = readOnlyModel("Assessment", "Due date", "Weight %");
        for (AssessmentItem it : draft.getItems()) {
            items.addRow(new Object[]{it.getTitle(), it.getDueDate() == null ? "NO DATE" : it.getDueDate(),
                    String.format("%.1f", it.getWeight())});
        }
        DefaultTableModel topics = readOnlyModel("Week", "Topic", "Weight %");
        for (Topic t : draft.getTopics()) {
            topics.addRow(new Object[]{t.getWeek() > 0 ? t.getWeek() : "", t.getName(),
                    t.getWeight() == null ? "" : String.format("%.0f", t.getWeight())});
        }
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(titled(sized(new JScrollPane(table(items)), 180),
                String.format("Assessments (total weight %.1f%%)", draft.totalWeight())));
        panel.add(titled(sized(new JScrollPane(table(topics)), 160), "Topics"));
        if (!draft.getWarnings().isEmpty()) {
            JTextArea warnings = textArea();
            warnings.setText("! " + String.join("\n! ", draft.getWarnings()));
            panel.add(titled(sized(new JScrollPane(warnings), 90), "Please check"));
        }
        panel.setPreferredSize(new Dimension(600, draft.getWarnings().isEmpty() ? 380 : 480));
        return JOptionPane.showConfirmDialog(window, panel, "Review syllabus: save these to " + code + "?",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION;
    }

    /** Manual entry (also the fallback when syllabus extraction fails): keeps asking until Cancel. */
    private void addAssessment(String code) {
        while (true) {
            JTextField title = new JTextField();
            JTextField due = new JTextField();
            JTextField weight = new JTextField();
            JPanel form = form("Title:", title, "Due date (YYYY-MM-DD, blank if none):", due, "Weight %:", weight);
            if (JOptionPane.showConfirmDialog(window, form, "Add assessment to " + code + " (Cancel to stop)",
                    JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) break;
            try {
                LocalDate date = due.getText().isBlank() ? null : LocalDate.parse(due.getText().trim());
                double w = Double.parseDouble(weight.getText().trim());
                controller.addAssessment(code, title.getText().trim(), date, w);
                window.setStatus("Added '" + title.getText().trim() + "' to " + code);
            } catch (DateTimeParseException e) {
                window.showError(new IllegalArgumentException("Dates must look like 2026-11-30"));
            } catch (NumberFormatException e) {
                window.showError(new IllegalArgumentException("Weight must be a number"));
            } catch (IllegalArgumentException | IllegalStateException e) {
                window.showError(e);
            }
        }
        showCourse();
    }

    private Course findInModel(String code) {
        for (int i = 0; i < courseModel.size(); i++) {
            if (courseModel.get(i).getCode().equals(code)) return courseModel.get(i);
        }
        return null;
    }

    private static <T extends javax.swing.JComponent> T sized(T c, int height) {
        c.setPreferredSize(new Dimension(560, height));
        return c;
    }
}
