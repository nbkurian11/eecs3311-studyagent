package studybetter.agent;

import studybetter.agent.MaterialSearchTool.Passage;
import studybetter.domain.Course;
import studybetter.domain.Explanation;
import studybetter.domain.Material;
import studybetter.llm.LLMClient;
import studybetter.llm.LLMException;
import studybetter.observer.DataObserver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The AI study agent. It gathers information with tools from the {@link ToolRegistry}
 * (retrieval over course materials, deadlines) and then asks the LLM, instead of just forwarding prompts.
 * <p>
 * Strategy pattern: depends only on the {@link LLMClient} interface, so Gemini or the mock can be plugged in.
 * Observer pattern: implements {@link DataObserver} and keeps a short memory of recent data changes
 * (grades, mastery, sessions) for later multi-step planning.
 */
public class StudyAgent implements DataObserver {

    /** Label used when an answer does not come from the student's own materials. */
    public static final String GENERAL_LABEL = "(General answer, not from your course materials.)";

    private static final int PASSAGES_PER_QUESTION = 4;
    private static final int MAX_SUMMARY_INPUT_CHARS = 30_000;
    private static final int MEMORY_SIZE = 20;

    private final LLMClient llm;
    private final ToolRegistry tools;
    private final Deque<String> recentEvents = new ArrayDeque<>();

    public StudyAgent(LLMClient llm, ToolRegistry tools) {
        this.llm = llm;
        this.tools = tools;
    }

    /**
     * F05: explains a concept for a course.
     * Step 1: search the course materials. Step 2a: if passages were found, ask the LLM to answer from them
     * and cite them. Step 2b: otherwise give a general answer, clearly labelled.
     * If the LLM fails but passages were found, the passages themselves are returned so the student still gets help.
     */
    public Explanation explain(Course course, String question) {
        if (question == null || question.isBlank()) throw new IllegalArgumentException("Question is required");
        MaterialSearchTool search = tools.get("material-search", MaterialSearchTool.class);
        List<Passage> passages = search.search(course.getId(), question, PASSAGES_PER_QUESTION);

        if (passages.isEmpty()) {
            String prompt = "You are a tutor for the university course " + describe(course) + ".\n"
                    + "Explain the following clearly for a student, with a short example. Keep it under 250 words.\n\n"
                    + "Question: " + question;
            try {
                return new Explanation(question, GENERAL_LABEL + "\n\n" + llm.generate(prompt).trim(), List.of(), false);
            } catch (LLMException e) {
                throw new IllegalStateException("AI request failed: " + e.getMessage()
                        + ". None of your materials for " + course.getCode() + " match this question either.");
            }
        }

        List<String> context = new ArrayList<>();
        Set<String> sources = new LinkedHashSet<>();
        for (Passage p : passages) {
            context.add("From \"" + p.materialTitle() + "\":\n" + p.text());
            sources.add(p.materialTitle());
        }
        String prompt = "You are a tutor for the university course " + describe(course) + ".\n"
                + "Explain the concept below for a student, using the sources above. Cite them as [Source n].\n"
                + "If the sources do not fully cover it, say which part comes from general knowledge.\n"
                + "Keep it under 300 words and include a short example.\n\n"
                + "Question: " + question;
        try {
            String answer = llm.generateWithContext(prompt, context).trim();
            return new Explanation(question, answer, new ArrayList<>(sources), true);
        } catch (LLMException e) {
            StringBuilder fallback = new StringBuilder("AI request failed: " + e.getMessage()
                    + "\nHere are the most relevant passages from your materials:\n");
            for (Passage p : passages) {
                fallback.append("\n[").append(p.materialTitle()).append("]\n").append(p.text()).append("\n");
            }
            return new Explanation(question, fallback.toString().trim(), new ArrayList<>(sources), true);
        }
    }

    /**
     * F01: summarizes a material into key points.
     * Throws {@link LLMException} if the LLM is unavailable; the caller then marks the summary as pending.
     */
    public String summarize(Material material) {
        String text = material.getText();
        if (text.isBlank()) throw new IllegalArgumentException("Material " + material.getTitle() + " has no text");
        if (text.length() > MAX_SUMMARY_INPUT_CHARS) text = text.substring(0, MAX_SUMMARY_INPUT_CHARS);
        String prompt = "Summarize the following course material for a student.\n"
                + "Give 5 to 8 key points as a bulleted list (\"- \" at the start of each line), "
                + "each one sentence, and nothing else.\n\n"
                + "Title: " + material.getTitle() + "\n\n" + text;
        String summary = llm.generate(prompt).trim();
        if (summary.isEmpty()) throw new LLMException("The AI returned an empty summary");
        return summary;
    }

    /** Observer callback: remembers recent changes (newest last) for later planning. */
    @Override
    public void onDataChanged(String event) {
        recentEvents.addLast(event);
        while (recentEvents.size() > MEMORY_SIZE) recentEvents.removeFirst();
    }

    public List<String> getRecentEvents() {
        return new ArrayList<>(recentEvents);
    }

    public ToolRegistry getTools() {
        return tools;
    }

    private static String describe(Course course) {
        return course.getCode() + (course.getName() == null ? "" : " (" + course.getName() + ")");
    }
}
