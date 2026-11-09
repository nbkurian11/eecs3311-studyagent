package studybetter.ui;

import studybetter.controller.AgentController;
import studybetter.domain.AssessmentItem;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.OptionalDouble;

/**
 * Grades tab (F04): pick a course and assessment, enter score / out of, and see the current grade and the
 * score needed on the remaining work to reach a target. Entering a grade notifies the dashboard (Observer).
 */
class GradesPanel extends TabPanel {

    private final JComboBox<String> courseBox = new JComboBox<>();
    private final JComboBox<AssessmentItem> itemBox = new JComboBox<>();
    private final JTextField score = new JTextField(6);
    private final JTextField outOf = new JTextField("100", 6);
    private final JTextField target = new JTextField("80", 6);
    private final JLabel currentGrade = new JLabel(" ");
    private final JLabel required = new JLabel(" ");
    private final DefaultTableModel model = readOnlyModel("Assessment", "Due", "Weight %", "Mark", "Percent");

    GradesPanel(AgentController controller, MainWindow window) {
        super(controller, window);
        courseBox.addActionListener(e -> window.attempt(this::reload));
        itemBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof AssessmentItem it) setText(it.getTitle() + String.format(" (%.0f%%)", it.getWeight()));
                return this;
            }
        });
        currentGrade.setFont(currentGrade.getFont().deriveFont(Font.BOLD, 18f));

        JPanel top = new JPanel(new GridLayout(0, 1));
        top.add(row(new JLabel("Course:"), courseBox, new JLabel("Assessment:"), itemBox));
        top.add(row(new JLabel("Score:"), score, new JLabel("Out of:"), outOf, button("Save grade", this::saveGrade)));
        top.add(row(currentGrade));
        top.add(row(new JLabel("Target final grade %:"), target, button("Required score", this::requiredScore), required));
        add(top, BorderLayout.NORTH);

        JScrollPane tableScroll = new JScrollPane(table(model));
        tableScroll.setBorder(BorderFactory.createTitledBorder("Assessments in this course"));
        add(tableScroll, BorderLayout.CENTER);
    }

    @Override
    void refresh() {
        fillCourses(courseBox, false);
        reload();
    }

    private void reload() {
        itemBox.removeAllItems();
        model.setRowCount(0);
        required.setText(" ");
        if (courseBox.getSelectedItem() == null) {
            currentGrade.setText("Add a course first (Courses tab).");
            return;
        }
        String code = selectedCourse(courseBox);
        for (AssessmentItem it : controller.findCourse(code).getItems()) {
            itemBox.addItem(it);
            model.addRow(new Object[]{it.getTitle(), it.getDueDate() == null ? "" : it.getDueDate(),
                    String.format("%.1f", it.getWeight()),
                    it.isGraded() ? fmt(it.getScore()) + " / " + fmt(it.getOutOf()) : "",
                    it.isGraded() ? String.format("%.1f%%", 100 * it.getScore() / it.getOutOf()) : ""});
        }
        OptionalDouble grade = controller.currentGrade(code);
        currentGrade.setText(grade.isPresent()
                ? String.format("Current grade in %s (graded work only): %.1f%%", code, grade.getAsDouble())
                : "No graded assessments in " + code + " yet.");
    }

    private void saveGrade() {
        AssessmentItem item = (AssessmentItem) itemBox.getSelectedItem();
        if (item == null) throw new IllegalStateException("This course has no assessments. Add them in the Courses tab.");
        double s = number(score, "Score");
        double o = number(outOf, "Out of");
        controller.enterGrade(item.getId(), s, o);
        score.setText("");
        reload();
        window.setStatus("Saved " + fmt(s) + "/" + fmt(o) + " for '" + item.getTitle() + "'");
    }

    private void requiredScore() {
        String code = selectedCourse(courseBox);
        required.setText(controller.requiredScore(code, number(target, "Target")).toString());
    }

    private static double number(JTextField field, String name) {
        try {
            return Double.parseDouble(field.getText().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number");
        }
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.format("%.1f", d);
    }
}
