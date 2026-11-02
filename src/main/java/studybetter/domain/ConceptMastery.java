package studybetter.domain;

public class ConceptMastery {
    private static final double RECENT_WEIGHT = 0.3;

    private final String courseId;
    private final String topic;
    private double level;
    private int attempts;

    public ConceptMastery(String courseId, String topic) {
        this.courseId = courseId;
        this.topic = topic;
    }


    public void record(boolean correct) {
        double value = correct ? 1.0 : 0.0;
        level = (attempts == 0) ? value : (1 - RECENT_WEIGHT) * level + RECENT_WEIGHT * value;
        attempts++;
    }

    public void reset() {
        level = 0;
        attempts = 0;
    }

    public String getLabel() {
        if (attempts == 0) return "Untested";
        if (level < 0.5) return "Weak";
        if (level < 0.8) return "Developing";
        return "Strong";
    }

    public String getCourseId() { return courseId; }
    public String getTopic() { return topic; }
    public double getLevel() { return level; }
    public int getAttempts() { return attempts; }

    @Override
    public String toString() {
        return String.format("%s: %.0f%% (%s, %d attempt%s)",
                topic, level * 100, getLabel(), attempts, attempts == 1 ? "" : "s");
    }
}