package studybetter.domain;

import java.util.UUID;

/**
 * A study file uploaded to a course (lecture notes, slides exported as text, readings).
 * Keeps the extracted text so the agent can search it, plus an AI summary.
 * If the LLM was unavailable at upload time, {@code summaryPending} is true and the summary can be retried.
 */
public class Material {
    private final String id;
    private String title;
    private String filePath;
    private String type;
    private String text;
    private String summary;
    private boolean summaryPending;

    public Material(String title, String filePath, String type) {
        this.id = UUID.randomUUID().toString();
        this.title = title;
        this.filePath = filePath;
        this.type = type;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getFilePath() { return filePath; }
    public String getType() { return type; }
    public String getText() { return text == null ? "" : text; }
    public void setText(String text) { this.text = text; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) {
        this.summary = summary;
        this.summaryPending = false;
    }
    public boolean isSummaryPending() { return summaryPending; }
    public void markSummaryPending() { this.summaryPending = true; }

    @Override
    public String toString() {
        String status = summaryPending ? "summary pending" : summary == null ? "no summary" : "summarized";
        return title + " (" + type + ", " + getText().length() + " chars, " + status + ")";
    }
}
