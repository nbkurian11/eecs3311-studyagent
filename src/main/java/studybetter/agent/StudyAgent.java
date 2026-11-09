package studybetter.agent;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import studybetter.agent.MaterialSearchTool.Passage;
import studybetter.agent.ProgressTool.CourseProgress;
import studybetter.domain.AssessmentItem;
import studybetter.domain.Course;
import studybetter.domain.Explanation;
import studybetter.domain.GradeResult;
import studybetter.domain.Material;
import studybetter.domain.PracticeAssessment;
import studybetter.domain.Question;
import studybetter.domain.QuestionGenerator;
import studybetter.domain.StudyPlan;
import studybetter.domain.StudyPriority;
import studybetter.domain.StudyTask;
import studybetter.domain.SyllabusDraft;
import studybetter.domain.Topic;
import studybetter.llm.LLMClient;
import studybetter.llm.LLMException;
import studybetter.observer.DataObserver;
import studybetter.service.DeadlineTracker.Deadline;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

/**
 * The AI study agent. It gathers information with tools from the {@link ToolRegistry}
 * (retrieval over course materials, deadlines) and then asks the LLM, instead of just forwarding prompts.
 * <p>
 * Strategy pattern: depends only on the {@link LLMClient} interface, so Gemini or the mock can be plugged in.
 * Observer pattern: implements {@link DataObserver}, keeps a short memory of recent data changes
 * (grades, mastery, sessions, deadlines) that it mentions when planning, and marks the current study plan
 * outdated when that data changes.
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
    /** The last plan built by {@link #buildStudyPlan}; marked outdated by {@link #onDataChanged}. */
    private volatile StudyPlan currentPlan;

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
            questions = parseQuestions(ask(prompt, context), countsByTopic, difficulty, dropped);
        } catch (JsonParseException firstError) {
            try {
                dropped[0] = 0;
                questions = parseQuestions(ask(prompt + "\n\nIMPORTANT: your previous reply was not "
                        + "valid. Reply with the JSON object only, in the exact shape shown.", context),
                        countsByTopic, difficulty, dropped);
            } catch (JsonParseException secondError) {
                throw new LLMException("The AI did not return valid questions (" + secondError.getMessage() + ")");
            }
        }
        if (dropped[0] > 0) warnings.add("Skipped " + dropped[0] + " badly formed question(s) from the AI.");
        return new QuestionBatch(questions, warnings);
    }

    /** Sends the prompt, with the retrieved passages as context when there are any. */
    private String ask(String prompt, List<String> context) {
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

    // ---------- F08: grading practice ----------

    /** A question sent to the LLM: GRADE a short answer, or EXPLAIN a wrong multiple choice answer. */
    private record GradingItem(int id, Question question, String answer, boolean needsScore) {}

    /** The LLM's verdict for one item; {@code score} is null for EXPLAIN items. */
    private record AiGrade(Double score, String feedback) {}

    /**
     * F08: grades a submitted quiz or mock exam.
     * Step 1: multiple choice is checked against the answer key (deterministic); blank answers get 0.
     * Step 2: short answers are sent to the LLM in one request for a score (1, 0.5 or 0) and feedback, together
     * with the wrong multiple choice answers, which only get an explanation (their score stays deterministic).
     * If the LLM is unavailable, short answers stay pending and multiple choice keeps its plain feedback.
     * When {@code previous} is given, its graded answers are kept and only its pending ones are graded again.
     */
    public GradeResult gradePractice(PracticeAssessment practice, GradeResult previous, boolean useLlm) {
        List<Question> questions = practice.getQuestions();
        List<String> answers = practice.getAnswers();
        GradeResult.QuestionResult[] results = new GradeResult.QuestionResult[questions.size()];
        List<GradingItem> toAsk = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            if (previous != null && i < previous.getResults().size() && !previous.getResults().get(i).pending()) {
                results[i] = previous.getResults().get(i);
                continue;
            }
            Question q = questions.get(i);
            String answer = i < answers.size() ? answers.get(i) : "";
            if (q.isMultipleChoice()) {
                results[i] = gradeMultipleChoice(q, answer);
                if (!results[i].correct() && !answer.isBlank()) toAsk.add(new GradingItem(i + 1, q, answer, false));
            } else if (answer.isBlank()) {
                results[i] = new GradeResult.QuestionResult(q.getText(), q.getTopic(), "", q.getAnswer(), 0, 1,
                        "No answer given.", false);
            } else {
                results[i] = GradeResult.QuestionResult.pending(q.getText(), q.getTopic(), answer, q.getAnswer(), 1,
                        "Pending: not graded yet.");
                toAsk.add(new GradingItem(i + 1, q, answer, true));
            }
        }

        List<String> warnings = new ArrayList<>();
        boolean hasShortAnswers = toAsk.stream().anyMatch(GradingItem::needsScore);
        if (!toAsk.isEmpty() && !useLlm) {
            if (hasShortAnswers) warnings.add("Short answers are pending: the AI is not available "
                    + "(no GEMINI_API_KEY in .env). Run 'grade-practice' later.");
        } else if (!toAsk.isEmpty()) {
            try {
                Map<Integer, AiGrade> grades = askForGrades(practice, toAsk);
                int missing = 0;
                for (GradingItem item : toAsk) {
                    AiGrade g = grades.get(item.id());
                    int i = item.id() - 1;
                    GradeResult.QuestionResult old = results[i];
                    if (g == null) {
                        if (item.needsScore()) missing++;
                    } else if (item.needsScore()) {
                        results[i] = new GradeResult.QuestionResult(old.question(), old.topic(), old.studentAnswer(),
                                old.correctAnswer(), g.score(), 1, g.feedback(), false);
                    } else {
                        results[i] = new GradeResult.QuestionResult(old.question(), old.topic(), old.studentAnswer(),
                                old.correctAnswer(), old.points(), old.maxPoints(),
                                old.feedback() + " " + g.feedback(), false);
                    }
                }
                if (missing > 0) warnings.add(missing + " short answer(s) were not graded by the AI and are still "
                        + "pending. Run 'grade-practice' again later.");
            } catch (LLMException e) {
                if (hasShortAnswers) warnings.add("Short answers are pending (AI request failed: " + e.getMessage()
                        + "). Run 'grade-practice' later.");
                else warnings.add("Could not get explanations for the wrong answers (" + e.getMessage() + ").");
            }
        }
        GradeResult result = new GradeResult(practice.getCourseId(), practice.getTitle(), List.of(results));
        warnings.forEach(result::addWarning);
        return result;
    }

    /** Deterministic: compares the chosen letter with the answer key. */
    private static GradeResult.QuestionResult gradeMultipleChoice(Question q, String answer) {
        String correctText = q.getAnswer() + ") " + q.getCorrectAnswerText();
        int chosen = Question.indexOfLetter(answer);
        String chosenText = chosen >= 0 && chosen < q.getOptions().size()
                ? Question.letter(chosen) + ") " + q.getOptions().get(chosen) : answer;
        boolean right = chosen >= 0 && Question.letter(chosen).equals(q.getAnswer());
        String feedback = right ? "Correct." : (answer.isBlank() ? "No answer given." : "Wrong.");
        return new GradeResult.QuestionResult(q.getText(), q.getTopic(), chosenText, correctText,
                right ? 1 : 0, 1, feedback, false);
    }

    /** One LLM request for all items; returns the valid verdicts by item id (retried once if unusable). */
    private Map<Integer, AiGrade> askForGrades(PracticeAssessment practice, List<GradingItem> items) {
        StringBuilder list = new StringBuilder();
        for (GradingItem item : items) {
            Question q = item.question();
            list.append("\nItem ").append(item.id()).append(item.needsScore() ? " (GRADE)" : " (EXPLAIN)")
                    .append("\nTopic: ").append(q.getTopic())
                    .append("\nQuestion: ").append(q.getText());
            if (item.needsScore()) {
                list.append("\nModel answer: ").append(q.getAnswer())
                        .append("\nStudent answer: ").append(item.answer());
            } else {
                for (int o = 0; o < q.getOptions().size(); o++) {
                    list.append("\n  ").append(Question.letter(o)).append(") ").append(q.getOptions().get(o));
                }
                list.append("\nCorrect answer: ").append(q.getAnswer())
                        .append("\nStudent chose: ").append(item.answer().toUpperCase());
            }
            list.append('\n');
        }
        String prompt = """
                You are grading a student's answers to "%s" for the university course %s.
                Write feedback to the student (1 to 3 sentences) for every item below.
                - GRADE items are short answers. Compare them with the model answer, accepting any wording with the
                  same meaning. "score" is 1 if correct and complete, 0.5 if partly correct, 0 if wrong.
                - EXPLAIN items are multiple-choice questions the student got wrong. Explain why the correct answer
                  is right and why the student's choice is wrong. Use "score": null.
                Reply with ONLY a JSON object, no explanation and no markdown, in exactly this shape:
                {"results":[{"id":1,"score":0.5,"feedback":"..."}]}
                %s""".formatted(practice.getTitle(), practice.getCourseCode(), list);
        Set<Integer> scored = new HashSet<>();
        Set<Integer> ids = new HashSet<>();
        for (GradingItem item : items) {
            ids.add(item.id());
            if (item.needsScore()) scored.add(item.id());
        }
        return askJson(prompt, List.of(), reply -> parseGrades(reply, ids, scored), "grades");
    }

    private static class RawGrades {
        List<RawGrade> results;
    }

    private static class RawGrade {
        Integer id;
        JsonElement score;
        String feedback;
    }

    private static Map<Integer, AiGrade> parseGrades(String reply, Set<Integer> ids, Set<Integer> scored) {
        RawGrades raw = GSON.fromJson(jsonObjectIn(reply), RawGrades.class);
        if (raw == null || raw.results == null) throw new JsonParseException("JSON has no 'results' list");
        Map<Integer, AiGrade> grades = new LinkedHashMap<>();
        for (RawGrade g : raw.results) {
            if (g == null || g.id == null || !ids.contains(g.id) || g.feedback == null || g.feedback.isBlank()) continue;
            Double score = null;
            if (scored.contains(g.id)) {
                score = toNumber(g.score);
                if (score == null || score < 0 || score > 1) continue;
                score = Math.round(score * 2) / 2.0; // only 0, 0.5 or 1
            }
            grades.put(g.id, new AiGrade(score, g.feedback.trim()));
        }
        if (grades.isEmpty()) throw new JsonParseException("no usable grades in the reply");
        return grades;
    }

    // ---------- F08: analyzing a graded past test ----------

    /**
     * F08: analyzes a graded past test (plain text).
     * Step 1: the LLM extracts each question, the marks earned and available, and a topic (matched to the course's
     * topics where possible) as JSON, which is validated. Step 2: for questions where marks were lost, the agent
     * retrieves related course material and asks the LLM to explain what was missing.
     * Throws {@link LLMException} if step 1 fails; if only step 2 fails, the marks are kept without explanations.
     */
    public GradeResult analyzePastTest(Course course, String title, String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("The test file is empty");
        if (text.length() > MAX_SYLLABUS_INPUT_CHARS) text = text.substring(0, MAX_SYLLABUS_INPUT_CHARS);
        String topics = course.getTopics().isEmpty() ? "(none listed)"
                : String.join("; ", course.getTopics().stream().map(Topic::getName).toList());
        String prompt = """
                Below is a graded test from the university course %s. Extract every question with the marks the
                student received. Known course topics: %s
                Reply with ONLY a JSON object, no explanation and no markdown, in exactly this shape:
                {"questions":[{"number":"1a","question":"...","studentAnswer":"...","marksEarned":3,"marksAvailable":5,
                 "topic":"..."}]}
                Rules:
                - marksEarned and marksAvailable are plain numbers; use null if the test does not show them.
                - studentAnswer is what the student wrote (shortened if long), or null if it is not shown.
                - topic is one of the known course topics when it fits, otherwise a short concept name (2 to 4 words).
                - Keep each question under 300 characters.

                Test:
                %s""".formatted(describe(course), topics, text);
        List<String> warnings = new ArrayList<>();
        List<GradeResult.QuestionResult> extracted = askJson(prompt, List.of(),
                reply -> parsePastTest(reply, course, warnings), "test questions and marks");

        List<Integer> lost = new ArrayList<>();
        for (int i = 0; i < extracted.size(); i++) if (!extracted.get(i).correct()) lost.add(i);
        List<GradeResult.QuestionResult> results = new ArrayList<>(extracted);
        if (!lost.isEmpty()) {
            try {
                Map<Integer, String> explanations = explainLostMarks(course, extracted, lost);
                for (int i : lost) {
                    String why = explanations.getOrDefault(i + 1, "No explanation was returned for this question.");
                    results.set(i, withFeedback(extracted.get(i), why));
                }
            } catch (LLMException e) {
                warnings.add("Could not explain the lost marks (" + e.getMessage() + "). The marks are still shown.");
                for (int i : lost) results.set(i, withFeedback(extracted.get(i), "Explanation unavailable."));
            }
        }
        GradeResult result = new GradeResult(course.getId(), title, results);
        warnings.forEach(result::addWarning);
        return result;
    }

    private static GradeResult.QuestionResult withFeedback(GradeResult.QuestionResult r, String feedback) {
        return new GradeResult.QuestionResult(r.question(), r.topic(), r.studentAnswer(), r.correctAnswer(),
                r.points(), r.maxPoints(), feedback, false);
    }

    private static class RawPastTest {
        List<RawPastQuestion> questions;
    }

    private static class RawPastQuestion {
        String number;
        String question;
        String studentAnswer;
        JsonElement marksEarned;
        JsonElement marksAvailable;
        String topic;
    }

    /** Validates the extracted questions; ones without readable marks are skipped with a warning. */
    private static List<GradeResult.QuestionResult> parsePastTest(String reply, Course course, List<String> warnings) {
        RawPastTest raw = GSON.fromJson(jsonObjectIn(reply), RawPastTest.class);
        if (raw == null || raw.questions == null) throw new JsonParseException("JSON has no 'questions' list");
        List<String> skipped = new ArrayList<>();
        List<GradeResult.QuestionResult> results = new ArrayList<>();
        for (RawPastQuestion q : raw.questions) {
            if (q == null || q.question == null || q.question.isBlank()) continue;
            String label = (q.number == null || q.number.isBlank() ? "" : "Q" + q.number.trim() + ". ")
                    + q.question.trim();
            Double earned = toNumber(q.marksEarned);
            Double available = toNumber(q.marksAvailable);
            if (earned == null || available == null || available <= 0 || earned < 0 || earned > available) {
                skipped.add(q.number == null ? q.question.trim() : "Q" + q.number.trim());
                continue;
            }
            String answer = q.studentAnswer == null || q.studentAnswer.equalsIgnoreCase("null") ? null : q.studentAnswer;
            results.add(new GradeResult.QuestionResult(label, matchCourseTopic(q.topic, course), answer, null,
                    earned, available, earned.equals(available) ? "Full marks." : "", false));
        }
        if (results.isEmpty()) throw new JsonParseException("no questions with readable marks in the reply");
        if (!skipped.isEmpty()) {
            warnings.add("Skipped " + String.join(", ", skipped) + ": the marks were not shown or not readable.");
        }
        return results;
    }

    /** Uses the course's spelling when the LLM's topic matches a course topic; otherwise keeps the LLM's name. */
    private static String matchCourseTopic(String given, Course course) {
        if (given == null || given.isBlank() || given.equalsIgnoreCase("null")) return null;
        String g = given.trim();
        for (Topic t : course.getTopics()) if (t.getName().equalsIgnoreCase(g)) return t.getName();
        for (Topic t : course.getTopics()) {
            String name = t.getName().toLowerCase();
            if (name.contains(g.toLowerCase()) || g.toLowerCase().contains(name)) return t.getName();
        }
        return g;
    }

    /** Step 2 of the past-test analysis: explanations by question number (1-based), using course material. */
    private Map<Integer, String> explainLostMarks(Course course, List<GradeResult.QuestionResult> questions,
                                                  List<Integer> lost) {
        MaterialSearchTool search = tools.get("material-search", MaterialSearchTool.class);
        List<String> context = new ArrayList<>();
        Set<String> searched = new HashSet<>();
        StringBuilder list = new StringBuilder();
        for (int i : lost) {
            GradeResult.QuestionResult q = questions.get(i);
            String topic = q.topic() == null ? q.question() : q.topic();
            if (searched.add(topic.toLowerCase())) {
                for (Passage p : search.search(course.getId(), topic, PASSAGES_PER_TOPIC)) {
                    if (context.size() < MAX_QUESTION_PASSAGES) context.add("From \"" + p.materialTitle() + "\":\n" + p.text());
                }
            }
            list.append("\nItem ").append(i + 1)
                    .append("\nQuestion: ").append(q.question())
                    .append("\nStudent answer: ").append(q.studentAnswer() == null ? "(not shown)" : q.studentAnswer())
                    .append("\nMarks: ").append(GradeResult.formatPoints(q.points())).append('/')
                    .append(GradeResult.formatPoints(q.maxPoints()))
                    .append("\nTopic: ").append(q.topic() == null ? "(unknown)" : q.topic()).append('\n');
        }
        String prompt = """
                A student lost marks on these questions of a graded test in the university course %s.
                For each item, explain to the student in 2 to 4 sentences what was probably missing or wrong and what
                to review.%s
                Reply with ONLY a JSON object, no explanation and no markdown, in exactly this shape:
                {"explanations":[{"id":1,"feedback":"..."}]}
                %s""".formatted(describe(course),
                context.isEmpty() ? "" : " Use the course material above where it helps.", list);
        Set<Integer> ids = new HashSet<>();
        lost.forEach(i -> ids.add(i + 1));
        return askJson(prompt, context, reply -> parseExplanations(reply, ids), "explanations");
    }

    private static class RawExplanations {
        List<RawGrade> explanations;
    }

    private static Map<Integer, String> parseExplanations(String reply, Set<Integer> ids) {
        RawExplanations raw = GSON.fromJson(jsonObjectIn(reply), RawExplanations.class);
        if (raw == null || raw.explanations == null) throw new JsonParseException("JSON has no 'explanations' list");
        Map<Integer, String> result = new LinkedHashMap<>();
        for (RawGrade e : raw.explanations) {
            if (e != null && e.id != null && ids.contains(e.id) && e.feedback != null && !e.feedback.isBlank()) {
                result.put(e.id, e.feedback.trim());
            }
        }
        if (result.isEmpty()) throw new JsonParseException("no usable explanations in the reply");
        return result;
    }

    /**
     * Asks the LLM and parses the reply with {@code parser}; if the parser rejects it (bad JSON or nothing usable),
     * asks once more with a firmer reminder. Throws {@link LLMException} if the second reply is unusable too.
     */
    private <T> T askJson(String prompt, List<String> context, Function<String, T> parser, String what) {
        try {
            return parser.apply(ask(prompt, context));
        } catch (JsonParseException firstError) {
            try {
                return parser.apply(ask(prompt + "\n\nIMPORTANT: your previous reply was not valid. "
                        + "Reply with the JSON object only, in the exact shape shown.", context));
            } catch (JsonParseException secondError) {
                throw new LLMException("The AI did not return valid " + what + " (" + secondError.getMessage() + ")");
            }
        }
    }

    // ---------- F12: study plan ----------

    public static final int PLAN_DAYS = 7;

    /**
     * F12: builds a one-week study plan with a multi-step agent loop.
     * Step 1 (tools): upcoming deadlines and weights from DeadlineTool; current grades, weak concepts and recent
     * study minutes from ProgressTool. Step 2 (reasoning): rank priorities by urgency x weight, boosted by weak
     * topics, low grades and little recent study ({@link PlanScheduler#rank}). Step 3 (LLM): ask for a strict JSON
     * schedule, validate every task and retry once on a bad reply. Step 4: trim any day that goes over the hours
     * available. If the LLM is unavailable ({@code useLlm} false) or fails, a deterministic plan sorted by
     * deadline x weight is built instead and the plan says so.
     */
    public StudyPlan buildStudyPlan(double hoursPerDay, boolean useLlm) {
        if (hoursPerDay < 0.5 || hoursPerDay > 12) {
            throw new IllegalArgumentException("Hours per day must be between 0.5 and 12");
        }
        int minutesPerDay = (int) Math.round(hoursPerDay * 60);
        LocalDate today = LocalDate.now();

        // step 1: gather facts with tools
        List<Deadline> deadlines = tools.get("deadlines", DeadlineTool.class).upcoming(null);
        List<CourseProgress> progress = tools.get("progress", ProgressTool.class).progress(7);
        if (progress.isEmpty()) throw new IllegalStateException("Add a course first.");

        // step 2: rank what matters most
        List<StudyPriority> priorities = PlanScheduler.rank(deadlines, progress, today);
        if (priorities.isEmpty()) {
            throw new IllegalStateException("Nothing to plan yet: add assessments with due dates in the next "
                    + PlanScheduler.HORIZON_DAYS + " days, or take a quiz so weak topics can be found.");
        }
        Map<String, List<String>> weakTopics = new LinkedHashMap<>();
        for (CourseProgress p : progress) {
            weakTopics.put(p.course().getCode(),
                    p.weakConcepts().stream().limit(3).map(m -> m.getTopic()).toList());
        }

        // step 3: ask the LLM for a schedule, or fall back to the deterministic plan
        String fallbackReason = useLlm ? null : "no GEMINI_API_KEY in .env, running on the offline mock";
        List<StudyTask> tasks = null;
        if (useLlm) {
            Set<String> codes = new HashSet<>();
            progress.forEach(p -> codes.add(p.course().getCode()));
            try {
                tasks = askJson(planPrompt(priorities, progress, today, minutesPerDay), List.of(),
                        reply -> parsePlan(reply, codes, today, minutesPerDay), "study plan");
            } catch (LLMException e) {
                fallbackReason = "AI request failed: " + e.getMessage();
            }
        }
        List<String> notes = new ArrayList<>();
        if (tasks == null) {
            tasks = PlanScheduler.fallbackSchedule(priorities, weakTopics, today, PLAN_DAYS, minutesPerDay);
            notes.add("Simple plan sorted by deadline x weight, because the AI was not available ("
                    + fallbackReason + ").");
        }

        // step 4: respect the hours available per day
        int[] cut = new int[1];
        tasks = PlanScheduler.fitToDailyLimit(tasks, minutesPerDay, cut);
        if (cut[0] > 0) notes.add(cut[0] + " min were cut so no day goes over " + minutesPerDay + " min.");
        long later = deadlines.stream()
                .filter(d -> d.item().getDueDate().isAfter(today.plusDays(PlanScheduler.HORIZON_DAYS))).count();
        if (later > 0) {
            notes.add(later + " assessment(s) due more than " + PlanScheduler.HORIZON_DAYS
                    + " days from now are not in this week's plan.");
        }

        StudyPlan plan = new StudyPlan(today, PLAN_DAYS, minutesPerDay, priorities, tasks, fallbackReason == null);
        notes.forEach(plan::addNote);
        currentPlan = plan;
        return plan;
    }

    /** The last plan built (possibly outdated), or null. */
    public StudyPlan getCurrentPlan() {
        return currentPlan;
    }

    private String planPrompt(List<StudyPriority> priorities, List<CourseProgress> progress, LocalDate today,
                              int minutesPerDay) {
        StringBuilder days = new StringBuilder();
        for (int d = 0; d < PLAN_DAYS; d++) {
            LocalDate date = today.plusDays(d);
            days.append(date).append(" (")
                    .append(date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)).append(") ");
        }
        StringBuilder ranked = new StringBuilder();
        for (int i = 0; i < priorities.size(); i++) {
            StudyPriority p = priorities.get(i);
            ranked.append(i + 1).append(". [").append(p.courseCode()).append("] ").append(p.title())
                    .append(p.isDeadline() ? " (due " + p.dueDate() + ")" : "")
                    .append(" | score ").append(p.score()).append(" | ").append(p.reason()).append('\n');
        }
        StringBuilder courses = new StringBuilder();
        progress.forEach(p -> courses.append("- ").append(ProgressTool.describe(p)).append('\n'));
        List<String> events = getRecentEvents();
        return """
                You are a study planner for a university student. Build a study schedule for these %d days:
                %s
                The student can study at most %d minutes per day. The total minutes on any date must not exceed that.

                Priorities, ranked by the planner (higher score = more urgent; scores combine deadline urgency
                x weight, weak topics, low grades and little recent study):
                %s
                Courses:
                %s
                Recent changes in the student's data: %s

                Rules:
                - Give more and earlier time to higher priorities, but spread work over several days.
                - Never schedule work for an assessment after its due date.
                - Each task is 25 to 90 minutes, names a concrete activity (e.g. "Practice quiz on Facade",
                  "Review lecture notes on graphs") and uses only the course codes listed above.
                - "reason" is one short sentence based on the facts above (deadline, weight, weak topic, grade).
                Reply with ONLY a JSON object, no explanation and no markdown, in exactly this shape:
                {"tasks":[{"date":"YYYY-MM-DD","course":"EECS3311","description":"...","minutes":60,"reason":"..."}]}
                """.formatted(PLAN_DAYS, days.toString().trim(), minutesPerDay, ranked, courses,
                events.isEmpty() ? "none" : String.join(", ", events));
    }

    /** Gson target for the LLM's plan JSON. */
    private static class RawPlan {
        List<RawTask> tasks;
    }

    private static class RawTask {
        String date;
        String course;
        String description;
        JsonElement minutes;
        String reason;
    }

    /**
     * Validates the LLM's plan: dates inside the week, known course codes, a description and at least
     * 15 minutes. Bad tasks are dropped; throws JsonParseException (so askJson retries) when none are usable.
     */
    private static List<StudyTask> parsePlan(String reply, Set<String> courseCodes, LocalDate start,
                                             int minutesPerDay) {
        RawPlan raw = GSON.fromJson(jsonObjectIn(reply), RawPlan.class);
        if (raw == null || raw.tasks == null) throw new JsonParseException("JSON has no 'tasks' list");
        LocalDate end = start.plusDays(PLAN_DAYS - 1L);
        List<StudyTask> tasks = new ArrayList<>();
        for (RawTask t : raw.tasks) {
            if (t == null || t.date == null || t.course == null || t.minutes == null
                    || t.description == null || t.description.isBlank()) continue;
            LocalDate date;
            int minutes;
            try {
                date = LocalDate.parse(t.date.trim());
                minutes = (int) Math.round(t.minutes.getAsDouble());
            } catch (RuntimeException e) {
                continue; // bad date or minutes: skip this task
            }
            if (date.isBefore(start) || date.isAfter(end) || minutes < PlanScheduler.MIN_BLOCK) continue;
            String code = courseCodes.stream().filter(c -> c.equalsIgnoreCase(t.course.trim()))
                    .findFirst().orElse(null);
            if (code == null) continue;
            tasks.add(new StudyTask(date, code, t.description.trim(), Math.min(minutes, minutesPerDay),
                    t.reason == null ? "" : t.reason.trim()));
        }
        if (tasks.isEmpty()) throw new JsonParseException("no usable tasks in the plan");
        return tasks;
    }

    /** Observer callback: remembers recent changes (newest last) and marks the current plan outdated. */
    @Override
    public void onDataChanged(String event) {
        recentEvents.addLast(event);
        while (recentEvents.size() > MEMORY_SIZE) recentEvents.removeFirst();
        StudyPlan plan = currentPlan;
        if (plan != null) plan.markOutdated(event);
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
