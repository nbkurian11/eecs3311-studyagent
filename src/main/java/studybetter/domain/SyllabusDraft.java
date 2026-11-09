package studybetter.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * F02: the assessments and topics the agent extracted from a syllabus, plus validation warnings.
 * It is only a draft: nothing is saved until the student reviews it and confirms
 * (see {@code AgentController.saveSyllabus}).
 */
public class SyllabusDraft {

    /** Weights are considered complete when they add up to 100 within this tolerance. */
    public static final double WEIGHT_TOLERANCE = 0.5;

    private final List<AssessmentItem> items = new ArrayList<>();
    private final List<Topic> topics = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public void addItem(AssessmentItem item) { items.add(item); }
    public void addTopic(Topic topic) { topics.add(topic); }
    public void addWarning(String warning) { warnings.add(warning); }

    public List<AssessmentItem> getItems() { return Collections.unmodifiableList(items); }
    public List<Topic> getTopics() { return Collections.unmodifiableList(topics); }
    public List<String> getWarnings() { return Collections.unmodifiableList(warnings); }

    public double totalWeight() {
        return items.stream().mapToDouble(AssessmentItem::getWeight).sum();
    }

    public boolean weightsTotal100() {
        return Math.abs(totalWeight() - 100) <= WEIGHT_TOLERANCE;
    }

    public List<AssessmentItem> itemsWithoutDates() {
        return items.stream().filter(i -> i.getDueDate() == null).toList();
    }

    public boolean isEmpty() {
        return items.isEmpty() && topics.isEmpty();
    }
}
