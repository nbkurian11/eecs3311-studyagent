package studybetter.ui;

import studybetter.controller.AgentController;
import studybetter.domain.Course;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.List;
import java.util.Objects;

/**
 * Base class for the tabs of {@link MainWindow}. Every tab talks only to the {@link AgentController} (Facade)
 * and uses the window for background work, the status bar and error dialogs.
 * {@link #refresh()} reloads the tab's data; the window calls it whenever the tab is opened.
 */
abstract class TabPanel extends JPanel {

    protected final AgentController controller;
    protected final MainWindow window;
    /** Folder of the last chosen file, shared by all tabs. */
    private static File lastFolder;

    protected TabPanel(AgentController controller, MainWindow window) {
        super(new BorderLayout(8, 8));
        this.controller = controller;
        this.window = window;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    }

    /** Reloads what the tab shows from the controller. */
    abstract void refresh();

    /** Refills a course-code combo box with the current courses, keeping the selection when it still exists. */
    protected void fillCourses(JComboBox<String> box, boolean includeAll) {
        Object previous = box.getSelectedItem();
        // pause listeners so refilling does not trigger a reload per item
        ActionListener[] listeners = box.getActionListeners();
        for (ActionListener l : listeners) box.removeActionListener(l);
        box.removeAllItems();
        if (includeAll) box.addItem(ALL_COURSES);
        List<Course> courses = controller.getCourses();
        for (Course c : courses) box.addItem(c.getCode());
        if (previous != null) box.setSelectedItem(previous);
        for (ActionListener l : listeners) box.addActionListener(l);
    }

    /** The selected course code; throws a clear message if there are no courses yet. */
    protected String selectedCourse(JComboBox<String> box) {
        Object code = box.getSelectedItem();
        if (code == null) throw new IllegalStateException("Add a course first (Courses tab).");
        return code.toString();
    }

    /** The selected course code, or null when "All courses" is selected. */
    protected String courseFilter(JComboBox<String> box) {
        Object code = box.getSelectedItem();
        return code == null || Objects.equals(code, ALL_COURSES) ? null : code.toString();
    }

    protected static final String ALL_COURSES = "All courses";

    /** A table model whose cells cannot be edited. */
    protected static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    /** A table with sensible defaults for read-only lists. */
    protected static JTable table(DefaultTableModel model) {
        JTable t = new JTable(model);
        t.setFillsViewportHeight(true);
        t.setRowHeight(22);
        t.getTableHeader().setReorderingAllowed(false);
        return t;
    }

    /** Lets the student pick a .txt or .md file; returns null if they cancel. */
    protected File chooseTextFile(String title) {
        JFileChooser chooser = new JFileChooser(lastFolder);
        chooser.setDialogTitle(title);
        chooser.setFileFilter(new FileNameExtensionFilter("Text or Markdown (*.txt, *.md)", "txt", "md"));
        if (chooser.showOpenDialog(window) != JFileChooser.APPROVE_OPTION) return null;
        lastFolder = chooser.getCurrentDirectory();
        return chooser.getSelectedFile();
    }

    // ---------- small layout helpers shared by the tabs ----------

    /** A button whose action runs through {@link MainWindow#attempt}, so errors become dialogs. */
    protected JButton button(String text, Runnable action) {
        JButton b = new JButton(text);
        b.addActionListener(e -> window.attempt(action));
        return b;
    }

    /** Components in a left-aligned row. */
    protected static JPanel row(Component... components) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (Component c : components) p.add(c);
        return p;
    }

    /** Label/field pairs in a two-column grid, for dialogs. */
    protected static JPanel form(Object... labelsAndFields) {
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
        for (int i = 0; i < labelsAndFields.length; i += 2) {
            p.add(new JLabel(labelsAndFields[i].toString()));
            p.add((Component) labelsAndFields[i + 1]);
        }
        return p;
    }

    /** A read-only, word-wrapping text area. */
    protected static JTextArea textArea() {
        JTextArea a = new JTextArea();
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        return a;
    }

    protected static <T extends JComponent> T titled(T c, String title) {
        c.setBorder(BorderFactory.createTitledBorder(title));
        return c;
    }
}
