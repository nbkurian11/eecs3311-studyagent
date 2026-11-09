package studybetter.service;

import studybetter.domain.AssessmentItem;
import studybetter.domain.Course;
import studybetter.domain.Material;
import studybetter.domain.Topic;
import studybetter.persistence.DataStore;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class CourseManager {

    private static final long MAX_MATERIAL_BYTES = 2_000_000;

    private List<Course> courses() {
        return DataStore.getInstance().getCourses();
    }

    private void save() {
        DataStore.getInstance().saveAll();
    }

    public Course addCourse(String code, String name, String term) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Course code is required");
        }
        if (findByCode(code).isPresent()) {
            throw new IllegalArgumentException("A course with code " + code.trim().toUpperCase() + " already exists");
        }
        Course course = new Course(code, name, term);
        courses().add(course);
        save();
        return course;
    }

    public void editCourse(String courseId, String newName, String newTerm) {
        Course course = getCourse(courseId);
        if (newName != null && !newName.isBlank()) course.setName(newName);
        if (newTerm != null && !newTerm.isBlank()) course.setTerm(newTerm);
        save();
    }

    public void deleteCourse(String courseId) {
        Course course = getCourse(courseId);
        courses().remove(course);
        save();
    }

    public void addMaterial(String courseId, Material material) {
        getCourse(courseId).addMaterial(material);
        save();
    }

    /**
     * Reads a .txt or .md file into a new (not yet saved) Material.
     * PDFs are not supported yet: the student should export the text first.
     */
    public Material readMaterialFile(Path path) {
        String text = readTextFile(path);
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        Material material = new Material(name.substring(0, dot), path.toAbsolutePath().toString(),
                name.substring(dot + 1).toLowerCase());
        material.setText(text);
        return material;
    }

    /** Reads a .txt or .md file (UTF-8, at most 2 MB, not empty). Used for materials and syllabi. */
    public String readTextFile(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File not found: " + path);
        }
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        if (!ext.equals("txt") && !ext.equals("md")) {
            throw new IllegalArgumentException("Only .txt and .md files are supported (got '" + name
                    + "'). Export PDFs or slides to text first.");
        }
        try {
            if (Files.size(path) > MAX_MATERIAL_BYTES) {
                throw new IllegalArgumentException("File is too large (limit 2 MB): " + name);
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            if (text.isBlank()) throw new IllegalArgumentException("File is empty: " + name);
            return text;
        } catch (MalformedInputException e) {
            throw new IllegalArgumentException("File is not UTF-8 text: " + name);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read " + name + ": " + e.getMessage());
        }
    }

    /** Saves a new summary for a material that is already stored. */
    public void updateSummary(Material material, String summary) {
        material.setSummary(summary);
        save();
    }

    public Course getCourse(String courseId) {
        return courses().stream()
                .filter(c -> c.getId().equals(courseId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No course with id " + courseId));
    }

    public Optional<Course> findByCode(String code) {
        String wanted = code.trim().toUpperCase();
        return courses().stream().filter(c -> c.getCode().equals(wanted)).findFirst();
    }

    public List<Course> getAllCourses() {
        return Collections.unmodifiableList(courses());
    }

    public void addAssessment(String courseId, studybetter.domain.AssessmentItem item) {
        getCourse(courseId).addItem(item);
        save();
    }

    /** F02: adds confirmed syllabus assessments and topics to a course and saves once. */
    public void importSyllabus(String courseId, List<AssessmentItem> items, List<Topic> topics) {
        Course course = getCourse(courseId);
        items.forEach(course::addItem);
        topics.forEach(course::addTopic);
        save();
    }
}