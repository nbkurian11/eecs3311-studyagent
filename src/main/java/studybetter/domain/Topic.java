package studybetter.domain;

/**
 * A syllabus topic. {@code week} is 0 when unknown; {@code weight} is the percent of the course the syllabus
 * says the topic is worth, or null when not stated. MockExam uses the weights to spread its questions.
 */
public class Topic {
    private String name;
    private int week;
    private Double weight;

    public Topic(String name, int week) {
        this(name, week, null);
    }

    public Topic(String name, int week, Double weight) {
        this.name = name;
        this.week = week;
        this.weight = weight;
    }

    public String getName() { return name; }
    public int getWeek() { return week; }
    public Double getWeight() { return weight; }

    @Override
    public String toString() {
        String label = week > 0 ? "Week " + week + ": " + name : name;
        return weight == null ? label : label + String.format(" (%.0f%%)", weight);
    }
}
