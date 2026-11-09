package studybetter.agent;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import studybetter.agent.MaterialSearchTool.Passage;
import studybetter.domain.AssessmentItem;
import studybetter.domain.Course;
import studybetter.domain.Explanation;
import studybetter.domain.Material;
import studybetter.domain.SyllabusDraft;
import studybetter.domain.Topic;
import studybetter.llm.LLMClient;
import studybetter.llm.LLMException;
import studybetter.observer.DataObserver;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
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
    private static final int MAX_SYLLABUS_INPUT_CHARS = 40_000;
    private static final int MEMORY_SIZE = 20;
    private static final Gson GSON = new Gson();

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

    /**
     * F02: extracts assessments and topics from syllabus text.
     * Step 1: ask the LLM for strict JSON. Step 2: parse it with Gson (retrying once if the format is bad).
     * Step 3: validate each entry and collect warnings (bad or missing dates, bad weights,
     * weights not totalling 100). The result is a draft; the caller saves it only after the student confirms.
     * Throws {@link LLMException} if the LLM is unavailable or never returns usable JSON.
     */
    public SyllabusDraft extractSyllabus(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Syllabus text is empty");
        if (text.length() > MAX_SYLLABUS_INPUT_CHARS) text = text.substring(0, MAX_SYLLABUS_INPUT_CHARS);
        String prompt = """
                Extract the graded assessments and the weekly topics from this course syllabus.
                Reply with ONLY a JSON object, no explanation and no markdown, in exactly this shape:
                {"assessments":[{"title":"Midterm","dueDate":"2026-10-20","weight":25}],
                 "topics":[{"name":"Design patterns","week":3}]}
                Rules:
                - dueDate is "YYYY-MM-DD", or null if the syllabus gives no exact date. Today is %s;
                  if a date has no year, use the year that fits the current term.
                - weight is a number (percent of the final grade) without a %% sign.
                - List each assessment separately (e.g. "Assignment 1", "Assignment 2") when the syllabus
                  gives separate dates; otherwise one entry for the group with its total weight.
                - week is the week number as an integer, or null if unknown.
                - Use empty lists if there are no assessments or no topics.

                Syllabus:
                %s""".formatted(LocalDate.now(), text);

        RawSyllabus raw;
        try {
            raw = parseSyllabusJson(llm.generate(prompt));
        } catch (JsonParseException firstError) {
            // one retry with a firmer reminder, since LLMs sometimes add prose or broken JSON
            try {
                raw = parseSyllabusJson(llm.generate(prompt
                        + "\n\nIMPORTANT: your previous reply was not valid JSON. Reply with the JSON object only."));
            } catch (JsonParseException secondError) {
                throw new LLMException("The AI did not return valid syllabus JSON (" + secondError.getMessage() + ")");
            }
        }
        return validateSyllabus(raw);
    }

    /** Gson target for the LLM's syllabus JSON. JsonElement lets "20%" or "Week 3" still be read. */
    private static class RawSyllabus {
        List<RawAssessment> assessments;
        List<RawTopic> topics;
    }

    private static class RawAssessment {
        String title;
        String dueDate;
        JsonElement weight;
    }

    private static class RawTopic {
        String name;
        JsonElement week;
    }

    /** Strips ```json fences and any text around the outer braces, then parses. */
    private static RawSyllabus parseSyllabusJson(String reply) {
        String json = reply == null ? "" : reply.replace("```json", "").replace("```", "").trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) throw new JsonParseException("no JSON object in the reply");
        RawSyllabus raw = GSON.fromJson(json.substring(start, end + 1), RawSyllabus.class);
        if (raw == null || (raw.assessments == null && raw.topics == null)) {
            throw new JsonParseException("JSON has no 'assessments' or 'topics'");
        }
        return raw;
    }

    /** Turns the raw JSON into domain objects, skipping or fixing bad entries and recording a warning for each. */
    private static SyllabusDraft validateSyllabus(RawSyllabus raw) {
        SyllabusDraft draft = new SyllabusDraft();
        Set<String> seenTitles = new HashSet<>();
        for (RawAssessment a : raw.assessments == null ? List.<RawAssessment>of() : raw.assessments) {
            if (a == null || a.title == null || a.title.isBlank()) {
                draft.addWarning("Skipped an assessment with no title.");
                continue;
            }
            String title = a.title.trim();
            if (!seenTitles.add(title.toLowerCase())) {
                draft.addWarning("Skipped duplicate assessment '" + title + "'.");
                continue;
            }
            LocalDate due = null;
            if (a.dueDate != null && !a.dueDate.isBlank() && !a.dueDate.equalsIgnoreCase("null")) {
                try {
                    due = LocalDate.parse(a.dueDate.trim());
                } catch (DateTimeParseException e) {
                    draft.addWarning("'" + title + "': could not read date '" + a.dueDate + "', left it blank.");
                }
            }
            Double weight = toNumber(a.weight);
            if (weight == null || weight < 0 || weight > 100) {
                draft.addWarning("'" + title + "': weight " + (weight == null ? "missing" : "out of range ("
                        + weight + ")") + ", set to 0. Fix it after import if needed.");
                weight = 0.0;
            }
            draft.addItem(new AssessmentItem(title, due, weight));
        }

        Set<String> seenTopics = new HashSet<>();
        for (RawTopic t : raw.topics == null ? List.<RawTopic>of() : raw.topics) {
            if (t == null || t.name == null || t.name.isBlank()) continue;
            if (!seenTopics.add(t.name.trim().toLowerCase())) continue;
            Double week = toNumber(t.week);
            draft.addTopic(new Topic(t.name.trim(), week == null || week < 0 ? 0 : week.intValue()));
        }

        if (draft.isEmpty()) {
            throw new LLMException("No assessments or topics were found in the syllabus");
        }
        if (!draft.getItems().isEmpty() && !draft.weightsTotal100()) {
            draft.addWarning(String.format("Weights total %.1f%%, not 100%%. Some assessments may be missing "
                    + "or mis-read.", draft.totalWeight()));
        }
        List<AssessmentItem> undated = draft.itemsWithoutDates();
        if (!undated.isEmpty()) {
            draft.addWarning(undated.size() + " assessment(s) have no due date: "
                    + String.join(", ", undated.stream().map(AssessmentItem::getTitle).toList())
                    + ". They will show under 'No due date' in deadlines.");
        }
        return draft;
    }

    /** Reads 20, "20", "20%" or "Week 3" as a number; returns null if there is no number. */
    private static Double toNumber(JsonElement e) {
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        if (e.getAsJsonPrimitive().isNumber()) return e.getAsDouble();
        String digits = e.getAsString().replaceAll("[^0-9.]", "");
        try {
            return digits.isEmpty() ? null : Double.parseDouble(digits);
        } catch (NumberFormatException ex) {
            return null;
        }
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
