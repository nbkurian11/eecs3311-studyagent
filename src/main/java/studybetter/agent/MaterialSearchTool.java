package studybetter.agent;

import studybetter.domain.Course;
import studybetter.domain.Material;
import studybetter.service.CourseManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Retrieval tool: keyword search over a course's uploaded materials.
 * Splits each material's text (and its summary) into passages, scores each passage by how many
 * question keywords it contains, and returns the best ones. The agent sends these passages to the
 * LLM as context so answers are grounded in the student's own course material.
 */
public class MaterialSearchTool implements Tool {

    /** A piece of a material that matched the search. */
    public record Passage(String materialTitle, String text, int score) {}

    private static final int MAX_PASSAGE_CHARS = 800;
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "are", "but", "not", "you", "your", "with", "this", "that", "from",
            "what", "when", "where", "which", "who", "why", "how", "does", "did", "can", "could",
            "would", "should", "about", "into", "than", "then", "them", "they", "their", "there",
            "explain", "describe", "define", "tell", "mean", "means", "between", "difference", "use",
            "used", "using", "please", "give", "example", "examples");

    private final CourseManager courseManager;

    public MaterialSearchTool(CourseManager courseManager) {
        this.courseManager = courseManager;
    }

    @Override
    public String getName() { return "material-search"; }

    @Override
    public String getDescription() {
        return "Finds the passages in a course's materials that best match a question. Input: courseId|question";
    }

    @Override
    public String execute(String input) {
        String[] parts = input == null ? new String[0] : input.split("\\|", 2);
        if (parts.length < 2) throw new IllegalArgumentException("Input must be courseId|question");
        List<Passage> passages = search(parts[0].trim(), parts[1].trim(), 3);
        if (passages.isEmpty()) return "No matching passages.";
        StringBuilder sb = new StringBuilder();
        for (Passage p : passages) {
            sb.append("[").append(p.materialTitle()).append("] ").append(p.text()).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * Returns up to {@code limit} matching passages, best first. A passage must contain at least two different
     * keywords (or the only keyword, for a one-keyword question) so a single shared word does not count.
     */
    public List<Passage> search(String courseId, String question, int limit) {
        Course course = courseManager.getCourse(courseId);
        Set<String> keywords = keywords(question);
        if (keywords.isEmpty()) return List.of();

        // a passage sharing only one common word with a longer question (e.g. "work") is not really about it
        int minDistinct = Math.min(2, keywords.size());
        List<Passage> matches = new ArrayList<>();
        for (Material m : course.getMaterials()) {
            List<String> chunks = splitIntoPassages(m.getText());
            if (m.getSummary() != null && !m.isSummaryPending()) chunks.add(m.getSummary());
            for (String chunk : chunks) {
                if (distinctMatches(chunk, keywords) < minDistinct) continue;
                matches.add(new Passage(m.getTitle(), chunk, score(chunk, keywords)));
            }
        }
        return matches.stream()
                .sorted(Comparator.comparingInt(Passage::score).reversed())
                .limit(limit)
                .toList();
    }

    /** Lower-case words of 3+ letters from the question, without common filler words. */
    static Set<String> keywords(String question) {
        Set<String> words = new LinkedHashSet<>();
        for (String w : question.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !STOP_WORDS.contains(w)) words.add(w);
        }
        return words;
    }

    /**
     * Score = 10 per distinct keyword found + 1 per extra occurrence, so a passage that mentions
     * several different keywords beats one that repeats a single keyword.
     */
    private static int score(String passage, Set<String> keywords) {
        String lower = passage.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String k : keywords) {
            int count = occurrences(lower, k);
            if (count > 0) score += 10 + (count - 1);
        }
        return score;
    }

    /** How many different keywords appear in the passage. */
    private static int distinctMatches(String passage, Set<String> keywords) {
        String lower = passage.toLowerCase(Locale.ROOT);
        return (int) keywords.stream().filter(k -> occurrences(lower, k) > 0).count();
    }

    /** Matches at the start of a word only: "observer" matches "observers", but "red" does not match "credit". */
    private static int occurrences(String lowerText, String keyword) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(keyword)).matcher(lowerText);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    /** Splits on blank lines (paragraphs); long paragraphs are cut into pieces of about MAX_PASSAGE_CHARS. */
    private static List<String> splitIntoPassages(String text) {
        List<String> result = new ArrayList<>();
        for (String para : text.split("\\R\\s*\\R")) {
            para = para.strip();
            while (para.length() > MAX_PASSAGE_CHARS) {
                int cut = para.lastIndexOf(' ', MAX_PASSAGE_CHARS);
                if (cut <= 0) cut = MAX_PASSAGE_CHARS;
                result.add(para.substring(0, cut).strip());
                para = para.substring(cut).strip();
            }
            if (!para.isEmpty()) result.add(para);
        }
        return result;
    }
}
