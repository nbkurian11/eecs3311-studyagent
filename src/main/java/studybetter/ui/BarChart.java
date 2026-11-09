package studybetter.ui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

/**
 * A small horizontal bar chart drawn with plain Swing (no chart library), used by the dashboard.
 * Each bar has a label on the left, a bar scaled to {@code maxValue} and its value text on the right.
 */
class BarChart extends JComponent {

    /** One bar. {@code color} may be null for the default color. */
    record Bar(String label, double value, String valueText, Color color) {}

    static final Color DEFAULT_COLOR = new Color(66, 133, 244);
    private static final int ROW_HEIGHT = 26;

    private List<Bar> bars = new ArrayList<>();
    private double maxValue = 100;
    private String emptyText = "No data yet";

    BarChart(String title) {
        setBorder(BorderFactory.createTitledBorder(title));
        setPreferredSize(new Dimension(300, 160));
    }

    /** Replaces the bars; {@code maxValue} is the value that fills the whole width (0 = use the largest bar). */
    void setData(List<Bar> bars, double maxValue, String emptyText) {
        this.bars = List.copyOf(bars);
        double largest = bars.stream().mapToDouble(Bar::value).max().orElse(0);
        this.maxValue = maxValue > 0 ? maxValue : Math.max(largest, 1);
        this.emptyText = emptyText;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        var in = getInsets();
        int x = in.left + 8;
        int y = in.top + 6;
        int width = getWidth() - in.left - in.right - 16;
        FontMetrics fm = g.getFontMetrics();

        if (bars.isEmpty()) {
            g.setColor(Color.GRAY);
            g.drawString(emptyText, x, y + fm.getAscent());
            g.dispose();
            return;
        }
        int labelWidth = Math.min(width / 3, bars.stream().mapToInt(b -> fm.stringWidth(b.label())).max().orElse(0) + 8);
        int valueWidth = bars.stream().mapToInt(b -> fm.stringWidth(b.valueText())).max().orElse(0) + 8;
        int barSpace = Math.max(10, width - labelWidth - valueWidth);

        for (Bar b : bars) {
            if (y + ROW_HEIGHT > getHeight() - in.bottom) break; // no room for more rows
            int textY = y + (ROW_HEIGHT + fm.getAscent()) / 2 - 2;
            g.setColor(getForeground());
            g.drawString(clip(b.label(), labelWidth - 8, fm), x, textY);
            int barWidth = (int) Math.round(barSpace * Math.max(0, Math.min(1, b.value() / maxValue)));
            g.setColor(new Color(230, 230, 230));
            g.fillRoundRect(x + labelWidth, y + 5, barSpace, ROW_HEIGHT - 10, 6, 6);
            g.setColor(b.color() == null ? DEFAULT_COLOR : b.color());
            g.fillRoundRect(x + labelWidth, y + 5, barWidth, ROW_HEIGHT - 10, 6, 6);
            g.setColor(getForeground());
            g.drawString(b.valueText(), x + labelWidth + barSpace + 6, textY);
            y += ROW_HEIGHT;
        }
        g.dispose();
    }

    /** Shortens a label with "..." so it fits in {@code max} pixels. */
    private static String clip(String text, int max, FontMetrics fm) {
        if (fm.stringWidth(text) <= max) return text;
        while (text.length() > 1 && fm.stringWidth(text + "...") > max) text = text.substring(0, text.length() - 1);
        return text + "...";
    }
}
