package studybetter.ui;

import studybetter.controller.AgentController;
import studybetter.controller.AgentController.DeadlineReport;
import studybetter.service.DeadlineTracker.Deadline;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Deadlines tab (F03): one table of overdue, due-soon, upcoming and undated assessments, with a course filter
 * and a button to mark the selected item complete.
 */
class DeadlinesPanel extends TabPanel {

    private static final String OVERDUE = "Overdue";
    private static final String DUE_SOON = "Due soon";

    private final JComboBox<String> courseBox = new JComboBox<>();
    private final DefaultTableModel model = readOnlyModel("Status", "Course", "Assessment", "Due", "Days left", "Weight %");
    private final JTable table = table(model);
    /** The deadline shown in each table row, in the same order. */
    private final List<Deadline> rows = new ArrayList<>();
    private final JLabel summary = new JLabel(" ");

    DeadlinesPanel(AgentController controller, MainWindow window) {
        super(controller, window);
        courseBox.addActionListener(e -> window.attempt(this::reload));
        table.setDefaultRenderer(Object.class, new StatusRenderer());

        add(row(new JLabel("Course:"), courseBox, button("Refresh", this::reload),
                button("Mark selected complete", this::markComplete)), BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(summary, BorderLayout.SOUTH);
    }

    @Override
    void refresh() {
        fillCourses(courseBox, true);
        reload();
    }

    private void reload() {
        String filter = courseFilter(courseBox);
        DeadlineReport report = controller.getDeadlines(filter);
        model.setRowCount(0);
        rows.clear();
        addRows(OVERDUE, report.overdue(), filter);
        addRows(DUE_SOON, report.dueSoon(), filter);
        List<Deadline> later = report.upcoming().stream().filter(d -> !report.dueSoon().contains(d)).toList();
        addRows("Upcoming", later, filter);
        addRows("No date", report.undated(), filter);
        summary.setText(rows.isEmpty() ? "Nothing due. Import a syllabus or add assessments in the Courses tab."
                : rows.size() + " open assessment(s).");
    }

    private void addRows(String status, List<Deadline> deadlines, String filter) {
        LocalDate today = LocalDate.now();
        for (Deadline d : deadlines) {
            if (filter != null && !d.course().getCode().equalsIgnoreCase(filter)) continue;
            LocalDate due = d.item().getDueDate();
            model.addRow(new Object[]{status, d.course().getCode(), d.item().getTitle(),
                    due == null ? "" : due.toString(),
                    due == null ? "" : ChronoUnit.DAYS.between(today, due),
                    String.format("%.1f", d.item().getWeight())});
            rows.add(d);
        }
    }

    private void markComplete() {
        int r = table.getSelectedRow();
        if (r < 0) throw new IllegalStateException("Select an assessment in the table first.");
        Deadline d = rows.get(table.convertRowIndexToModel(r));
        controller.markComplete(d.item().getId());
        reload();
        window.setStatus("Marked '" + d.item().getTitle() + "' complete");
    }

    /** Overdue rows in red, due-soon rows in orange. */
    private static class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            if (!selected) {
                Object status = table.getValueAt(row, 0);
                if (OVERDUE.equals(status)) c.setForeground(new Color(180, 0, 0));
                else if (DUE_SOON.equals(status)) c.setForeground(new Color(190, 100, 0));
                else c.setForeground(table.getForeground());
            }
            return c;
        }
    }
}
