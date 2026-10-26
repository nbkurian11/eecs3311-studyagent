package studybetter.domain;

import java.util.UUID;

public class Material {
    private final String id;
    private String title;
    private String filePath;
    private String type;
    private String summary;

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
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
}