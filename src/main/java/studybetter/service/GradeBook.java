package studybetter.service;

import studybetter.domain.AssessmentItem;
import studybetter.domain.Course;
import studybetter.observer.Subject;
import studybetter.persistence.DataStore;

import java.util.List;
import java.util.OptionalDouble;

public class GradeBook extends Subject {

    public record RequiredScore(double neededPercent, boolean achievable) {
        @Override
        public String toString() {
            return achievable
                    ? String.format("You need %.1f%% on the remaining work", neededPercent)
                    : String.format("Not achievable: you would need %.1f%% on the remaining work", neededPercent);
        }
    }

    public void enterGrade(String itemId, double score, double outOf) {
        AssessmentItem item = findItem(itemId);
        item.setMark(score, outOf);              // validates 0 <= score <= outOf
        DataStore.getInstance().saveAll();
        notifyObservers("grade");                // Observer: dashboard / agent get told
    }

    public OptionalDouble currentGrade(String courseId) {
        List<AssessmentItem> graded = course(courseId).getItems().stream()
                .filter(AssessmentItem::isGraded).toList();
        double gradedWeight = graded.stream().mapToDouble(AssessmentItem::getWeight).sum();
        if (gradedWeight == 0) return OptionalDouble.empty();
        return OptionalDouble.of(earnedPoints(graded) / gradedWeight * 100);
    }

    public RequiredScore requiredScore(String courseId, double targetPercent) {
        List<AssessmentItem> items = course(courseId).getItems();
        double totalWeight = items.stream().mapToDouble(AssessmentItem::getWeight).sum();
        if (totalWeight == 0) {
            throw new IllegalStateException("This course has no assessment weights. Add weights first.");
        }
        List<AssessmentItem> graded = items.stream().filter(AssessmentItem::isGraded).toList();
        double remainingWeight = items.stream().filter(i -> !i.isGraded())
                .mapToDouble(AssessmentItem::getWeight).sum();
        if (remainingWeight == 0) {
            throw new IllegalStateException("Every assessment is already graded.");
        }
        double needed = (targetPercent - earnedPoints(graded)) / remainingWeight * 100;
        return new RequiredScore(Math.max(needed, 0), needed <= 100);
    }

    private double earnedPoints(List<AssessmentItem> graded) {
        return graded.stream()
                .mapToDouble(i -> i.getScore() / i.getOutOf() * i.getWeight())
                .sum();
    }

    private Course course(String courseId) {
        return DataStore.getInstance().getCourses().stream()
                .filter(c -> c.getId().equals(courseId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No course with id " + courseId));
    }

    private AssessmentItem findItem(String itemId) {
        return DataStore.getInstance().getCourses().stream()
                .flatMap(c -> c.getItems().stream())
                .filter(i -> i.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No assessment with id " + itemId));
    }
}