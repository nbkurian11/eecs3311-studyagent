package studybetter.ui;

import studybetter.controller.AgentController;
import studybetter.domain.ConceptMastery;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.List;

/**
 * Mastery tab (F09): each tested topic of a course with its mastery level (shown as a bar), label and number of
 * attempts, weakest first. The levels come from graded quizzes, mock exams and past tests.
 */
class MasteryPanel extends TabPanel {

    private final JComboBox<String> courseBox = new JComboBox<>();
    private final DefaultTableModel model = readOnlyModel("Topic", "Level", "Label", "Attempts");
    private final JLabel summary = new JLabel(" ");

    MasteryPanel(AgentController controller, MainWindow window) {
        super(controller, window);
        courseBox.addActionListener(e -> window.attempt(this::reload));
        JTable table = table(model);
        table.getColumnModel().getColumn(1).setCellRenderer(new LevelRenderer());

        add(row(new JLabel("Course:"), courseBox), BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(summary, BorderLayout.SOUTH);
    }

    @Override
    void refresh() {
        fillCourses(courseBox, false);
        reload();
    }

    private void reload() {
        model.setRowCount(0);
        if (courseBox.getSelectedItem() == null) {
            summary.setText("Add a course first (Courses tab).");
            return;
        }
        String code = selectedCourse(courseBox);
        List<ConceptMastery> list = controller.getMastery(code);
        for (ConceptMastery m : list) {
            model.addRow(new Object[]{m.getTopic(), (int) Math.round(m.getLevel() * 100), m.getLabel(), m.getAttempts()});
        }
        List<String> weak = controller.getWeakTopics(code);
        summary.setText(list.isEmpty() ? "No mastery data for " + code + " yet. Take a quiz in the Practice tab."
                : "Weak topics: " + (weak.isEmpty() ? "none" : String.join(", ", weak)));
    }

    /** Draws the level (0-100) as a progress bar with the percentage on it. */
    private static class LevelRenderer extends JProgressBar implements TableCellRenderer {
        LevelRenderer() {
            super(0, 100);
            setStringPainted(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focus, int row, int column) {
            int level = value instanceof Integer i ? i : 0;
            setValue(level);
            setString(level + "%");
            return this;
        }
    }
}
