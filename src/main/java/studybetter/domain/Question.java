package studybetter.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * One practice question, tagged with the topic (concept) it tests so grading can update mastery.
 * Multiple choice questions have options and store the correct option's letter ("A", "B", ...) as the answer;
 * short-answer questions have no options and store a model answer.
 */
public class Question {

    private final String text;
    private final List<String> options;
    private final String answer;
    private final String topic;
    private final String difficulty;

    public Question(String text, List<String> options, String answer, String topic, String difficulty) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Question text is required");
        if (answer == null || answer.isBlank()) throw new IllegalArgumentException("Question answer is required");
        this.text = text.trim();
        this.options = options == null ? new ArrayList<>() : new ArrayList<>(options);
        this.answer = answer.trim();
        this.topic = topic;
        this.difficulty = difficulty;
        if (isMultipleChoice() && indexOfLetter(this.answer) >= this.options.size()) {
            throw new IllegalArgumentException("Answer " + answer + " is not one of the options");
        }
    }

    public String getText() { return text; }
    public List<String> getOptions() { return List.copyOf(options); }
    public String getAnswer() { return answer; }
    public String getTopic() { return topic; }
    public String getDifficulty() { return difficulty; }

    public boolean isMultipleChoice() { return !options.isEmpty(); }

    /** For multiple choice: the text of the correct option. For short answer: the model answer. */
    public String getCorrectAnswerText() {
        return isMultipleChoice() ? options.get(indexOfLetter(answer)) : answer;
    }

    /** 0 becomes "A", 1 becomes "B", and so on. */
    public static String letter(int index) {
        return String.valueOf((char) ('A' + index));
    }

    /** "A" becomes 0, "b" becomes 1; returns -1 if the text is not a single letter. */
    public static int indexOfLetter(String text) {
        if (text == null || text.trim().length() != 1) return -1;
        char c = Character.toUpperCase(text.trim().charAt(0));
        return (c >= 'A' && c <= 'Z') ? c - 'A' : -1;
    }
}
