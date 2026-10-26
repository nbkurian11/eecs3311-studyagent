package studybetter.domain;

import java.time.LocalDate;
import java.util.UUID;

public class AssessmentItem {
    private final String id;
    private String title;
    private LocalDate dueDate;
    private double weight;
    private Double score;
    private double outOf = 100;
    private boolean completed;

    public AssessmentItem(String title, LocalDate dueDate, double weight) {
        this.id = UUID.randomUUID().toString();
        this.title = title;
        this.dueDate = dueDate;
        this.weight = weight;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public LocalDate getDueDate() { return dueDate; }
    public double getWeight() { return weight; }
    public Double getScore() { return score; }
    public double getOutOf() { return outOf; }
    public boolean isCompleted() { return completed; }

    public void setCompleted(boolean completed) { this.completed = completed; }

    public void setMark(double score, double outOf) {
        if (score < 0 || outOf <= 0 || score > outOf) {
            throw new IllegalArgumentException("Mark must be between 0 and " + outOf);
        }
        this.score = score;
        this.outOf = outOf;
        this.completed = true;
    }

    public boolean isGraded() { return score != null; }

    public boolean isOverdue(LocalDate today) {
        return !completed && dueDate != null && dueDate.isBefore(today);
    }
}