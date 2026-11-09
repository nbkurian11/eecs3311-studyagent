package studybetter.ui;

import studybetter.controller.AgentController;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * The Swing GUI (default user interface). A JTabbedPane with one {@link TabPanel} per feature area; every tab
 * reaches the application only through the {@link AgentController} Facade, exactly like the CLI.
 * <p>
 * Slow work (every LLM call) goes through {@link #runInBackground}, which uses a {@link SwingWorker} so the
 * window never freezes, shows "Working..." in the status bar, and reports errors in a dialog.
 */
public class MainWindow extends JFrame {

    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel status = new JLabel("Ready");
    private final JProgressBar progress = new JProgressBar();
    private boolean busy;

    public MainWindow(AgentController controller) {
        super("StudyBetter");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 600));
        setSize(1100, 750);
        setLocationRelativeTo(null);

        tabs.addTab("Courses", new CoursesPanel(controller, this));
        tabs.addTab("Deadlines", new DeadlinesPanel(controller, this));
        tabs.addTab("Grades", new GradesPanel(controller, this));
        tabs.addTab("Learn", new LearnPanel(controller, this));
        tabs.addTab("Practice", new PracticePanel(controller, this));
        tabs.addTab("Mastery", new MasteryPanel(controller, this));
        tabs.addTab("Sessions", new SessionsPanel(controller, this));
        tabs.addTab("Planner", new PlannerPanel(controller, this));
        tabs.addTab("Dashboard", new DashboardPanel(controller, this));
        // reload a tab every time it is opened, so it shows changes made in other tabs
        tabs.addChangeListener(e -> refreshSelectedTab());

        JPanel root = new JPanel(new BorderLayout());
        if (!controller.isUsingRealLlm()) {
            JLabel warning = new JLabel("  No GEMINI_API_KEY in .env: running on the offline mock, "
                    + "so AI features (summaries, syllabus import, practice) are unavailable.");
            warning.setOpaque(true);
            warning.setBackground(new Color(255, 236, 179));
            warning.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            root.add(warning, BorderLayout.NORTH);
        }
        root.add(tabs, BorderLayout.CENTER);
        root.add(statusBar(), BorderLayout.SOUTH);
        setContentPane(root);
        refreshSelectedTab();
    }

    /** Creates the window on the Swing event thread and shows it. */
    public static void launch(AgentController controller) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // the default look and feel is fine
            }
            new MainWindow(controller).setVisible(true);
        });
    }

    private JPanel statusBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        progress.setIndeterminate(true);
        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(140, 14));
        bar.add(status, BorderLayout.CENTER);
        bar.add(progress, BorderLayout.EAST);
        return bar;
    }

    private void refreshSelectedTab() {
        if (tabs.getSelectedComponent() instanceof TabPanel tab) attempt(tab::refresh);
    }

    /**
     * Runs {@code work} on a background thread (SwingWorker) and then {@code onSuccess} with its result on the
     * Swing thread. Only one task runs at a time; exceptions are shown in a dialog.
     */
    public <T> void runInBackground(String message, Callable<T> work, Consumer<T> onSuccess) {
        if (busy) {
            JOptionPane.showMessageDialog(this, "Still working on the previous request. Please wait.",
                    "StudyBetter", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        setBusy(true, message);
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return work.call();
            }

            @Override
            protected void done() {
                setBusy(false, "Ready");
                try {
                    T result = get();
                    attempt(() -> onSuccess.accept(result));
                } catch (ExecutionException e) {
                    showError(e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    /** Runs a quick action on the Swing thread and shows any exception in a dialog. */
    public void attempt(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            showError(e);
        }
    }

    /** Validation problems are shown as warnings; anything else as an error. */
    public void showError(Throwable e) {
        boolean validation = e instanceof IllegalArgumentException || e instanceof IllegalStateException;
        String text = e.getMessage() == null ? e.toString() : e.getMessage();
        JOptionPane.showMessageDialog(this, text, validation ? "Please check" : "Error",
                validation ? JOptionPane.WARNING_MESSAGE : JOptionPane.ERROR_MESSAGE);
    }

    public void info(String message) {
        JOptionPane.showMessageDialog(this, message, "StudyBetter", JOptionPane.INFORMATION_MESSAGE);
    }

    public boolean confirm(String message) {
        return JOptionPane.showConfirmDialog(this, message, "StudyBetter", JOptionPane.YES_NO_OPTION)
                == JOptionPane.YES_OPTION;
    }

    public void setStatus(String text) {
        status.setText(text);
    }

    private void setBusy(boolean busy, String message) {
        this.busy = busy;
        progress.setVisible(busy);
        status.setText(busy ? "Working... " + message : message);
    }
}
