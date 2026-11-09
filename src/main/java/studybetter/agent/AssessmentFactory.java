package studybetter.agent;

import studybetter.domain.MockExam;
import studybetter.domain.PracticeAssessment;
import studybetter.domain.PracticeSettings;
import studybetter.domain.Quiz;

/**
 * Simple Factory pattern: one static method decides which {@link PracticeAssessment} subclass to create
 * from a type name, so the controller and UI only ever work with the PracticeAssessment type and
 * adding a new kind of practice changes only this class.
 */
public final class AssessmentFactory {

    private AssessmentFactory() {
    }

    /** @param type "quiz" or "mock-exam" (also accepts "mock" and "mockexam") */
    public static PracticeAssessment create(String type, PracticeSettings settings) {
        if (type == null) throw new IllegalArgumentException("Practice type is required");
        return switch (type.trim().toLowerCase()) {
            case Quiz.TYPE -> new Quiz(settings);
            case MockExam.TYPE, "mock", "mockexam" -> new MockExam(settings);
            default -> throw new IllegalArgumentException("Unknown practice type '" + type
                    + "'. Use 'quiz' or 'mock-exam'.");
        };
    }
}
