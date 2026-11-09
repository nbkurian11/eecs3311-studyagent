package studybetter.ui;

import studybetter.controller.AgentController;
import studybetter.domain.StudyPlan;
import studybetter.domain.StudyPriority;
import studybetter.domain.StudyTask;
import studybetter.observer.DataObserver;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridLayout;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Planner tab (F12): enter the hours available per day and let the agent build a one-week plan (in the
 * background). Shows the ranked priorities and a table of tasks with the reason for each.
 * Observer pattern: the panel is attached through {@link AgentController#addObserver}, so when grades, mastery,
 * sessions or deadlines change, the "plan is outdated" notice appears straight away.
 */
class PlannerPanel extends TabPanel implements DataObserver {

    private final JSpinner hours = new JSpinner(new SpinnerNumberModel(2.0, 0.5, 12.0, 0.5));
    private final JLabel outdated = new JLabel();
    private final JLabel summary = new JLabel(" ");
    private final DefaultTableModel model = readOnlyModel("Date", "Day", "Course", "Task", "Minutes", "Why");
    private final JTextArea priorities = textArea();
    private final JTextArea notes = textArea();

    PlannerPanel(AgentController controller, MainWindow window) {
        super(controller, window);
        controller.addObserver(this);

        outdated.setOpaque(true);
        outdated.setBackground(new Color(255, 236, 179));
        outdated.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        outdated.setVisible(false);

        JPanel top = new JPanel(new GridLayout(0, 1));
        top.add(row(new JLabel("Hours available per day:"), hours, button("Generate plan", this::generate), summary));
        top.add(outdated);
        add(top, BorderLayout.NORTH);

        JTable table = table(model);
        table.setDefaultRenderer(Object.class, new TooltipRenderer());
        int[] widths = {85, 40, 80, 300, 60, 340};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            if (i != 3 && i != 5) table.getColumnModel().getColumn(i).setMinWidth(widths[i]); // keep short columns readable
        }

        JPanel side = new JPanel(new GridLayout(2, 1, 6, 6));
        side.add(titled(new JScrollPane(priorities), "Priorities (most urgent first)"));
        side.add(titled(new JScrollPane(notes), "Notes"));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(table), side);
        split.setResizeWeight(0.72);
        add(split, BorderLayout.CENTER);
    }

    @Override
    void refresh() {
        controller.getCurrentPlan().ifPresentOrElse(this::show, () -> {
            summary.setText("No plan yet. Choose your hours and click 'Generate plan'.");
            outdated.setVisible(false);
        });
    }

    /** A grade, mastery, session or deadline change: show the outdated notice (on the Swing thread). */
    @Override
    public void onDataChanged(String event) {
        SwingUtilities.invokeLater(this::showOutdatedNotice);
    }

    private void generate() {
        double h = ((Number) hours.getValue()).doubleValue();
        window.runInBackground("planning your week", () -> controller.generateStudyPlan(h), (StudyPlan plan) -> {
            show(plan);
            window.setStatus(plan.isFromAi() ? "Plan built by the AI" : "Simple plan built (AI not available)");
        });
    }

    private void show(StudyPlan plan) {
        model.setRowCount(0);
        for (StudyTask t : plan.getTasks()) {
            model.addRow(new Object[]{t.date(), t.date().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    t.courseCode(), t.description(), t.minutes(), t.reason()});
        }
        summary.setText(String.format("%s to %s: %d min planned (max %d min/day)%s", plan.getStartDate(),
                plan.getEndDate(), plan.getTotalMinutes(), plan.getMinutesPerDay(),
                plan.isFromAi() ? ", built by the AI" : ", simple fallback plan"));

        StringBuilder p = new StringBuilder();
        int i = 1;
        for (StudyPriority pr : plan.getPriorities()) {
            p.append(i++).append(". ").append(pr.courseCode()).append(": ").append(pr.title())
                    .append(String.format(" [score %.1f]%n   ", pr.score())).append(pr.reason()).append("\n");
        }
        priorities.setText(p.toString());
        priorities.setCaretPosition(0);
        notes.setText(plan.getNotes().isEmpty() ? "(none)" : "! " + String.join("\n! ", plan.getNotes()));
        showOutdatedNotice();
    }

    private void showOutdatedNotice() {
        StudyPlan plan = controller.getCurrentPlan().orElse(null);
        boolean show = plan != null && plan.isOutdated();
        if (show) {
            outdated.setText("This plan is outdated: your " + plan.getOutdatedBecause()
                    + " data changed after it was made. Click 'Generate plan' for an updated one.");
        }
        outdated.setVisible(show);
    }

    /** Shows the full text of long cells (task, reason) as a tooltip. */
    private static class TooltipRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            setToolTipText(value == null ? null : value.toString());
            return c;
        }
    }
}
