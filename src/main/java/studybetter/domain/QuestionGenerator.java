package studybetter.domain;

import java.util.List;
import java.util.Map;

/**
 * What a {@link PracticeAssessment} needs in order to get questions. {@code StudyAgent} implements it
 * (retrieval plus the LLM), so Quiz and MockExam do not depend on the agent or the LLM directly,
 * and a test can pass a fake generator.
 */
public interface QuestionGenerator {

    /** Questions plus any notes for the student (e.g. "no materials for topic X"). */
    record QuestionBatch(List<Question> questions, List<String> warnings) {}

    /**
     * Generates up to {@code countsByTopic.get(topic)} questions for each topic.
     *
     * @param purpose a short description used in the prompt, e.g. "a practice quiz"
     * @throws studybetter.llm.LLMException if the LLM is unavailable or never returns usable questions
     */
    QuestionBatch generateQuestions(Course course, Map<String, Integer> countsByTopic,
                                    String difficulty, String purpose);
}
