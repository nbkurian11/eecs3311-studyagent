package studybetter.ui;

import studybetter.controller.AgentController;
import studybetter.domain.Explanation;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;

/**
 * Learn tab (F05): ask the agent to explain a concept. The agent searches the course materials first and
 * shows which ones it used; otherwise the answer is labelled as general knowledge.
 */
class LearnPanel extends TabPanel {

    private final JComboBox<String> courseBox = new JComboBox<>();
    private final JTextField question = new JTextField(45);
    private final JTextArea answer = textArea();
    private final JLabel sources = new JLabel(" ");

    LearnPanel(AgentController controller, MainWindow window) {
        super(controller, window);
        JButton explain = button("Explain", this::explain);
        question.addActionListener(e -> window.attempt(this::explain)); // Enter key

        add(row(new JLabel("Course:"), courseBox, new JLabel("Question:"), question, explain), BorderLayout.NORTH);
        add(titled(new JScrollPane(answer), "Explanation"), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(sources, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
    }

    @Override
    void refresh() {
        fillCourses(courseBox, false);
    }

    private void explain() {
        String code = selectedCourse(courseBox);
        String q = question.getText().trim();
        if (q.isEmpty()) throw new IllegalArgumentException("Type a question first.");
        answer.setText("Thinking...");
        sources.setText(" ");
        window.runInBackground("explaining", () -> controller.explainConcept(code, q), (Explanation e) -> {
            answer.setText(e.getText());
            answer.setCaretPosition(0);
            if (!e.isFromCourseMaterials()) sources.setText("General answer: not from your course materials.");
            else sources.setText("Sources: " + String.join(", ", e.getSources()));
        });
    }
}
