package studybetter.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class Course {
    private final String id;
    private String code;
    private String name;
    private String term;
    private final List<Material> materials = new ArrayList<>();
    private final List<AssessmentItem> items = new ArrayList<>();
    private final List<Topic> topics = new ArrayList<>();

    public Course(String code, String name, String term) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Course code is required");
        this.id = UUID.randomUUID().toString();
        this.code = code.trim().toUpperCase();
        this.name = name;
        this.term = term;
    }

    public void addMaterial(Material m) { materials.add(m); }
    public void addItem(AssessmentItem item) { items.add(item); }
    public void addTopic(Topic t) { topics.add(t); }

    public String getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getTerm() { return term; }
    public void setName(String name) { this.name = name; }
    public void setTerm(String term) { this.term = term; }

    public List<Material> getMaterials() { return Collections.unmodifiableList(materials); }
    public List<AssessmentItem> getItems() { return Collections.unmodifiableList(items); }
    public List<Topic> getTopics() { return Collections.unmodifiableList(topics); }

    @Override
    public String toString() { return code + " - " + name + " (" + term + ")"; }
}