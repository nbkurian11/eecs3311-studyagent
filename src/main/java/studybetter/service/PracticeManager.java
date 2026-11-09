package studybetter.service;

import studybetter.domain.PracticeAssessment;
import studybetter.persistence.DataStore;

import java.util.Comparator;
import java.util.List;

/**
 * Keeps the practice tests (quizzes and mock exams) the student has submitted, so their answers can be
 * graded (F08) and shown as history. Saved through the {@link DataStore} singleton.
 */
public class PracticeManager {

    /** Saves a submitted practice (adding it the first time, updating it after that). */
    public void save(PracticeAssessment practice) {
        if (!practice.isSubmitted()) throw new IllegalStateException("Only submitted practice is saved");
        List<PracticeAssessment> all = DataStore.getInstance().getPractices();
        if (all.stream().noneMatch(p -> p.getId().equals(practice.getId()))) all.add(practice);
        DataStore.getInstance().saveAll();
    }

    public PracticeAssessment findById(String id) {
        return DataStore.getInstance().getPractices().stream()
                .filter(p -> p.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No saved practice with id " + id));
    }

    /** A course's practice that is not graded yet or still has pending short answers, newest first. */
    public List<PracticeAssessment> getPracticesToGrade(String courseId) {
        return getPractices(courseId).stream().filter(PracticeAssessment::needsGrading).toList();
    }

    /** A course's submitted practice, newest first. */
    public List<PracticeAssessment> getPractices(String courseId) {
        return DataStore.getInstance().getPractices().stream()
                .filter(p -> courseId.equals(p.getCourseId()))
                .sorted(Comparator.comparing(PracticeAssessment::getSubmittedAt).reversed())
                .toList();
    }
}
