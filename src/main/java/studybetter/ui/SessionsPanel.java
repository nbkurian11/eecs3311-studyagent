package studybetter.ui;

import studybetter.controller.AgentController;
import studybetter.domain.Course;
import studybetter.domain.StudySession;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sessions tab (F10): start and end a study session, list finished sessions and show total study minutes.
 * Ending a session notifies the dashboard (Observer).
 */
class SessionsPanel extends TabPanel {

    private final JComboBox<String> courseBox = new JComboBox<>();
    private final JTextField topic = new JTextField(20);
    private final JLabel active = new JLabel(" ");
    private final JComboBox<String> filterBox = new JComboBox<>();
    private final DefaultTableModel model = readOnlyModel("Course", "Topic", "Date", "Start", "Minutes");
    private final JLabel total = new JLabel(" ");

    SessionsPanel(AgentController controller, MainWindow window) {
        super(controller, window);
        filterBox.addActionListener(e -> window.attempt(this::reload));

        JPanel top = new JPanel(new GridLayout(0, 1));
        top.add(row(new JLabel("Course:"), courseBox, new JLabel("Topic:"), topic,
                button("Start session", this::start), button("End session", this::end)));
        top.add(row(active));
        top.add(row(new JLabel("Show sessions for:"), filterBox));
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table(model)), BorderLayout.CENTER);
        add(total, BorderLayout.SOUTH);
    }

    @Override
    void refresh() {
        fillCourses(courseBox, false);
        fillCourses(filterBox, true);
        reload();
    }

    private void reload() {
        active.setText(controller.getActiveSession()
                .map(s -> "Running: " + s.getTopic() + " (" + codeOf(s.getCourseId()) + "), started "
                        + s.getStart().toLocalTime().withNano(0))
                .orElse("No session running."));
        String filter = courseFilter(filterBox);
        List<StudySession> sessions = controller.getSessions(filter).stream()
                .sorted(Comparator.comparing(StudySession::getStart).reversed())
                .toList();
        model.setRowCount(0);
        int minutes = 0;
        for (StudySession s : sessions) {
            model.addRow(new Object[]{codeOf(s.getCourseId()), s.getTopic(), s.getStart().toLocalDate(),
                    s.getStart().toLocalTime().withNano(0).withSecond(0), s.getMinutes()});
            minutes += s.getMinutes();
        }
        total.setText(String.format("Total: %d min (%.1f h) over %d session(s)%s", minutes, minutes / 60.0,
                sessions.size(), filter == null ? "" : " in " + filter));
    }

    private void start() {
        String code = selectedCourse(courseBox);
        String t = topic.getText().trim();
        if (t.isEmpty()) throw new IllegalArgumentException("Type what you are studying (topic).");
        controller.startSession(code, t);
        reload();
        window.setStatus("Session started");
    }

    private void end() {
        StudySession s = controller.endSession();
        reload();
        window.setStatus("Session ended: " + s.getMinutes() + " min");
        if (controller.sessionNeedsConfirmation(s)) {
            window.info("This session lasted " + s.getMinutes() + " minutes. Did you forget to end it?");
        }
    }

    private String codeOf(String courseId) {
        Map<String, String> codes = new HashMap<>();
        for (Course c : controller.getCourses()) codes.put(c.getId(), c.getCode());
        return codes.getOrDefault(courseId, "(deleted)");
    }
}
