package studybetter.domain;

import java.util.List;

/**
 * Result of explaining a concept (F05): the answer text and the course materials it was based on.
 * {@code fromCourseMaterials} is false when no matching material was found and the answer is general knowledge.
 */
public class Explanation {
    private final String question;
    private final String text;
    private final List<String> sources;
    private final boolean fromCourseMaterials;

    public Explanation(String question, String text, List<String> sources, boolean fromCourseMaterials) {
        this.question = question;
        this.text = text;
        this.sources = List.copyOf(sources);
        this.fromCourseMaterials = fromCourseMaterials;
    }

    public String getQuestion() { return question; }
    public String getText() { return text; }
    public List<String> getSources() { return sources; }
    public boolean isFromCourseMaterials() { return fromCourseMaterials; }
}
