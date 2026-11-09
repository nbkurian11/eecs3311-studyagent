package studybetter.agent;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import studybetter.agent.MaterialSearchTool.Passage;
import studybetter.domain.AssessmentItem;
import studybetter.domain.Course;
import studybetter.domain.Explanation;
import studybetter.domain.Material;
import studybetter.domain.Question;
import studybetter.domain.QuestionGenerator;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The AI study agent. It gathers information with tools from the {@link ToolRegistry}
 * (retrieval over course materials, deadlines) and then asks the LLM, instead of just forwarding prompts.
 * <p>
 * Strategy pattern: depends only on the {@link LLMClient} interface, so Gemini or the mock can be plugged in.
 * Observer pattern: implements {@link DataObserver} and keeps a short memory of recent data changes
 * (grades, mastery, sessions) for later multi-step planning.
 * Also implements {@link QuestionGenerator}, so Quiz and MockExam (Template Method) get their questions from here.
 */
public class StudyAgent implements DataObserver, QuestionGenerator {

    /** Label used when an answer does not come from the student's own materials. */
    public static final String GENERAL_LABEL = "(General answer, not from your course materials.)";

    private static final int PASSAGES_PER_QUESTION = 4;
    private static final int MAX_SUMMARY_INPUT_CHARS = 30_000;
    private static final int MAX_SYLLABUS_INPUT_CHARS = 40_000;
    private static final int MEMORY_SIZE = 20;
    private static final int PASSAGES_PER_TOPIC = 2;
    private static final int MAX_QUESTION_PASSAGES = 8;
    private static final Gson GSON = new Gson();

    private final LLMClient llm;
    private final ToolRegistry tools;
    private final Deque<String> recentEvents = new ArrayDeque<>();
    private final Random random = new Random();

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
                 "topics":[{"name":"Design patterns","week":3,"weight":null}]}
                Rules:
                - dueDate is "YYYY-MM-DD", or null if the syllabus gives no exact date. Today is %s;
                  if a date has no year, use the year that fits the current term.
                - weight is a number (percent of the final grade) without a %% sign.
                - List each assessment separately (e.g. "Assignment 1", "Assignment 2") when the syllabus
                  gives separate dates; otherwise one entry for the group with its total weight.
                - week is the week number as an integer, or null if unknown.
                - topic weight is the percent of the course or final exam the syllabus says the topic is
                  worth, as a number, or null if the syllabus does not say (most do not; never guess).
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
        JsonElement weight;
    }

    /** Strips ```json fences and any text around the outer braces, leaving just the JSON object. */
    private static String jsonObjectIn(String reply) {
        String json = reply == null ? "" : reply.replace("```json", "").replace("```", "").trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) throw new JsonParseException("no JSON object in the reply");
        return json.substring(start, end + 1);
    }

    private static RawSyllabus parseSyllabusJson(String reply) {
        RawSyllabus raw = GSON.fromJson(jsonObjectIn(reply), RawSyllabus.class);
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
            Double weight = toNumber(t.weight);
            if (weight != null && (weight <= 0 || weight > 100)) weight = null;
            draft.addTopic(new Topic(t.name.trim(), week == null || week < 0 ? 0 : week.intValue(), weight));
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

    /**
     * F06/F07: generates practice questions, called by Quiz and MockExam during their build() template method.
     * Step 1: retrieve a few passages per topic from the course materials (topics with none are noted and
     * generated from the topic name only). Step 2: ask the LLM for strict JSON. Step 3: parse and validate
     * every question, retrying once if the reply is not usable. Step 4: shuffle multiple choice options so the
     * right answer is not always "A".
     */
    @Override
    public QuestionBatch generateQuestions(Course course, Map<String, Integer> countsByTopic,
                                           String difficulty, String purpose) {
        if (countsByTopic == null || countsByTopic.isEmpty()) throw new IllegalArgumentException("No topics given");
        List<String> warnings = new ArrayList<>();
        MaterialSearchTool search = tools.get("material-search", MaterialSearchTool.class);
        List<String> context = new ArrayList<>();
        List<String> noMaterial = new ArrayList<>();
        for (String topic : countsByTopic.keySet()) {
            List<Passage> found = search.search(course.getId(), topic, PASSAGES_PER_TOPIC);
            if (found.isEmpty()) noMaterial.add(topic);
            for (Passage p : found) {
                if (context.size() < MAX_QUESTION_PASSAGES) context.add("From \"" + p.materialTitle() + "\":\n" + p.text());
            }
        }
        if (!noMaterial.isEmpty()) {
            warnings.add("No course materials found for " + String.join(", ", noMaterial)
                    + "; those questions are based on the topic name only.");
        }

        StringBuilder plan = new StringBuilder();
        int total = 0;
        for (Map.Entry<String, Integer> e : countsByTopic.entrySet()) {
            plan.append("- \"").append(e.getKey()).append("\": ").append(e.getValue()).append('\n');
            total += e.getValue();
        }
        String prompt = """
                Write %d practice questions for %s in the university course %s.
                Difficulty: %s. Questions per topic:
                %s
                Use mostly multiple-choice questions (4 options, exactly one correct) and about one in four
                short-answer questions that need a 1 to 3 sentence answer.%s
                Reply with ONLY a JSON object, no explanation and no markdown, in exactly this shape:
                {"questions":[
                 {"type":"mcq","topic":"<topic name>","text":"...","options":["...","...","...","..."],"answer":"B"},
                 {"type":"short","topic":"<topic name>","text":"...","answer":"a short model answer"}]}
                Rules:
                - "topic" must be exactly one of the topic names listed above.
                - For "mcq", "answer" is the letter of the correct option; do not put letters inside the options.
                - Each question must make sense on its own (no "according to Source 1").""".formatted(
                total, purpose, describe(course), difficulty, plan.toString().trim(),
                context.isEmpty() ? "" : "\nBase the questions on the course material above where possible.");

        List<Question> questions;
        int[] dropped = new int[1];
        try {
            questions = parseQuestions(askForQuestions(prompt, context), countsByTopic, difficulty, dropped);
        } catch (JsonParseException firstError) {
            try {
                dropped[0] = 0;
                questions = parseQuestions(askForQuestions(prompt + "\n\nIMPORTANT: your previous reply was not "
                        + "valid. Reply with the JSON object only, in the exact shape shown.", context),
                        countsByTopic, difficulty, dropped);
            } catch (JsonParseException secondError) {
                throw new LLMException("The AI did not return valid questions (" + secondError.getMessage() + ")");
            }
        }
        if (dropped[0] > 0) warnings.add("Skipped " + dropped[0] + " badly formed question(s) from the AI.");
        return new QuestionBatch(questions, warnings);
    }

    private String askForQuestions(String prompt, List<String> context) {
        return context.isEmpty() ? llm.generate(prompt) : llm.generateWithContext(prompt, context);
    }

    /** Gson target for one question in the LLM's JSON. The answer may come back as "B", "b)" or the option text. */
    private static class RawQuestions {
        List<RawQuestion> questions;
    }

    private static class RawQuestion {
        String type;
        String topic;
        String text;
        List<String> options;
        String answer;
    }

    /**
     * Turns the LLM reply into validated questions, keeping at most the requested number per topic.
     * Throws {@link JsonParseException} when the reply has no usable question at all, so the caller retries.
     */
    private List<Question> parseQuestions(String reply, Map<String, Integer> countsByTopic,
                                          String difficulty, int[] dropped) {
        RawQuestions raw = GSON.fromJson(jsonObjectIn(reply), RawQuestions.class);
        if (raw == null || raw.questions == null) throw new JsonParseException("JSON has no 'questions' list");
        Map<String, Integer> remaining = new LinkedHashMap<>(countsByTopic);
        List<Question> valid = new ArrayList<>();
        for (RawQuestion r : raw.questions) {
            Question q = toQuestion(r, remaining.keySet(), difficulty);
            if (q == null) {
                dropped[0]++;
                continue;
            }
            int left = remaining.get(q.getTopic());
            if (left == 0) continue; // more than asked for this topic
            remaining.put(q.getTopic(), left - 1);
            valid.add(q);
        }
        if (valid.isEmpty()) throw new JsonParseException("no usable questions in the reply");
        return valid;
    }

    /** Validates one raw question; returns null if it cannot be used. */
    private Question toQuestion(RawQuestion r, Set<String> topics, String difficulty) {
        if (r == null || r.text == null || r.text.isBlank() || r.answer == null || r.answer.isBlank()) return null;
        String topic = matchTopic(r.topic, topics);
        if (topic == null) return null;

        boolean mcq = r.options != null && r.options.size() >= 2 && !"short".equalsIgnoreCase(r.type);
        if (!mcq) return new Question(r.text, List.of(), r.answer, topic, difficulty);

        List<String> options = new ArrayList<>();
        for (String o : r.options) {
            if (o == null || o.isBlank()) return null;
            String clean = o.trim().replaceFirst("^[A-Fa-f][).:]\\s+", "");
            if (options.stream().anyMatch(clean::equalsIgnoreCase)) return null; // duplicate option
            options.add(clean);
        }
        if (options.size() > 6) return null;

        // the answer may be a letter ("B", "b)", "Option B") or the option's text
        String a = r.answer.trim().replaceFirst("(?i)^option\\s+", "").replaceFirst("[).:]$", "");
        int correct = Question.indexOfLetter(a);
        if (correct < 0) {
            for (int i = 0; i < options.size(); i++) {
                if (options.get(i).equalsIgnoreCase(r.answer.trim())) correct = i;
            }
        }
        if (correct < 0 || correct >= options.size()) return null;

        String correctText = options.get(correct);
        Collections.shuffle(options, random);
        return new Question(r.text, options, Question.letter(options.indexOf(correctText)), topic, difficulty);
    }

    /** Finds which requested topic the LLM meant: exact name first, then one name containing the other. */
    private static String matchTopic(String given, Set<String> topics) {
        if (given == null || given.isBlank()) return topics.size() == 1 ? topics.iterator().next() : null;
        String g = given.trim().toLowerCase();
        for (String t : topics) if (t.equalsIgnoreCase(g)) return t;
        for (String t : topics) {
            String lower = t.toLowerCase();
            if (lower.contains(g) || g.contains(lower)) return t;
        }
        return topics.size() == 1 ? topics.iterator().next() : null;
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
