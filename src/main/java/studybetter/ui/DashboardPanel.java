package studybetter.ui;

import studybetter.controller.AgentController;
import studybetter.observer.DataObserver;
import studybetter.service.DeadlineTracker.Deadline;
import studybetter.service.ProgressDashboard.CourseStats;
import studybetter.service.ProgressDashboard.Stats;
import studybetter.service.ProgressDashboard.TopicStats;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Dashboard tab (F11). Observer pattern: this panel is registered with the {@code ProgressDashboard} (through
 * {@link AgentController#addDashboardObserver}); when a grade, session, mastery update or completed deadline
 * changes the data, the dashboard recomputes its stats and this panel redraws itself, even while another tab
 * is open. Shows study minutes and current grade per course, the weakest topics, and upcoming / completed
 * deadlines, with bar charts drawn by {@link BarChart}.
 */
class DashboardPanel extends TabPanel implements DataObserver {

    private static final Color WEAK = new Color(219, 68, 55);
    private static final Color DEVELOPING = new Color(244, 160, 0);
    private static final Color STRONG = new Color(15, 157, 88);

    private final JLabel header = new JLabel(" ");
    private final JLabel updated = new JLabel(" ");
    private final BarChart minutesChart = new BarChart("Study minutes per course");
    private final BarChart gradeChart = new BarChart("Current grade per course");
    private final BarChart weakChart = new BarChart("Weakest topics (mastery)");
    private final JTextArea deadlines = textArea();

    DashboardPanel(AgentController controller, MainWindow window) {
        super(controller, window);
        controller.addDashboardObserver(this);
        header.setFont(header.getFont().deriveFont(Font.BOLD, 16f));

        JPanel top = new JPanel(new BorderLayout());
        top.add(header, BorderLayout.WEST);
        top.add(updated, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridLayout(2, 2, 10, 10));
        grid.add(minutesChart);
        grid.add(gradeChart);
        grid.add(weakChart);
        grid.add(titled(new JScrollPane(deadlines), "Deadlines"));
        add(grid, BorderLayout.CENTER);
    }

    /** Opening the tab recomputes everything (so new courses and assessments show up too). */
    @Override
    void refresh() {
        show(controller.refreshDashboard());
    }

    /**
     * Called by the ProgressDashboard after it recomputed its stats. That can happen on a background thread
     * (e.g. grading in a SwingWorker), so the redraw is handed to the Swing thread.
     */
    @Override
    public void onDataChanged(String event) {
        SwingUtilities.invokeLater(() -> show(controller.getDashboard()));
    }

    private void show(Stats stats) {
        header.setText(String.format("%d course(s), %d min studied in total", stats.courses().size(),
                stats.totalStudyMinutes()));
        updated.setText("Updated " + stats.updatedAt().toLocalTime().withNano(0) + " (after: " + stats.lastEvent() + ")");

        List<BarChart.Bar> minutes = new ArrayList<>();
        List<BarChart.Bar> grades = new ArrayList<>();
        for (CourseStats c : stats.courses()) {
            minutes.add(new BarChart.Bar(c.courseCode(), c.studyMinutes(), c.studyMinutes() + " min", null));
            if (c.currentGrade() != null) {
                grades.add(new BarChart.Bar(c.courseCode(), c.currentGrade(),
                        String.format("%.1f%%", c.currentGrade()), gradeColor(c.currentGrade())));
            }
        }
        minutesChart.setData(minutes, 0, "No courses yet");
        gradeChart.setData(grades, 100, "No grades entered yet");

        List<BarChart.Bar> weak = new ArrayList<>();
        for (TopicStats t : stats.weakestTopics()) {
            weak.add(new BarChart.Bar(t.topic() + " (" + t.courseCode() + ")", t.level() * 100,
                    String.format("%.0f%% %s", t.level() * 100, t.label()), gradeColor(t.level() * 100)));
        }
        weakChart.setData(weak, 100, "No quizzes graded yet");

        StringBuilder sb = new StringBuilder("Upcoming:\n");
        if (stats.upcoming().isEmpty()) sb.append("  (none)\n");
        for (Deadline d : stats.upcoming()) sb.append("  ").append(line(d)).append('\n');
        sb.append("\nCompleted:\n");
        if (stats.completed().isEmpty()) sb.append("  (none)\n");
        for (Deadline d : stats.completed()) sb.append("  ").append(line(d)).append('\n');
        deadlines.setText(sb.toString());
        deadlines.setCaretPosition(0);
    }

    private static String line(Deadline d) {
        String due = d.item().getDueDate() == null ? "no date" : d.item().getDueDate().toString();
        String mark = d.item().isGraded()
                ? String.format(", scored %.0f%%", 100 * d.item().getScore() / d.item().getOutOf()) : "";
        return String.format("%s  %s: %s (worth %.0f%%%s)", due, d.course().getCode(), d.item().getTitle(),
                d.item().getWeight(), mark);
    }

    /** Red below 50, orange below 80, green otherwise (same thresholds as mastery labels). */
    private static Color gradeColor(double percent) {
        return percent < 50 ? WEAK : percent < 80 ? DEVELOPING : STRONG;
    }
}
