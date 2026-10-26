package studybetter.service;

import studybetter.domain.Course;
import studybetter.domain.Material;
import studybetter.persistence.DataStore;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class CourseManager {

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
}