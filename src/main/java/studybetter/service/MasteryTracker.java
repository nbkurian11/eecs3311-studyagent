package studybetter.service;

import studybetter.domain.ConceptMastery;
import studybetter.domain.GradeResult;
import studybetter.observer.Subject;
import studybetter.persistence.DataStore;

import java.util.Comparator;
import java.util.List;

public class MasteryTracker extends Subject {

    public void update(GradeResult result) {
        for (GradeResult.QuestionResult q : result.getResults()) {
            if (q.topic() == null || q.topic().isBlank()) continue;
            find(result.getCourseId(), q.topic()).record(q.correct());
        }
        DataStore.getInstance().saveAll();
        notifyObservers("mastery");
    }

    public List<ConceptMastery> getMastery(String courseId) {
        return DataStore.getInstance().getMastery().stream()
                .filter(m -> m.getCourseId().equals(courseId))
                .sorted(Comparator.comparingDouble(ConceptMastery::getLevel))
                .toList();
    }

    public List<ConceptMastery> getWeakConcepts(String courseId) {
        return getMastery(courseId).stream()
                .filter(m -> m.getAttempts() > 0 && m.getLevel() < 0.5)
                .toList();
    }

    public void reset(String courseId, String topic) {
        find(courseId, topic).reset();
        DataStore.getInstance().saveAll();
        notifyObservers("mastery");
    }

    private ConceptMastery find(String courseId, String topic) {
        List<ConceptMastery> all = DataStore.getInstance().getMastery();
        return all.stream()
                .filter(m -> m.getCourseId().equals(courseId) && m.getTopic().equalsIgnoreCase(topic))
                .findFirst()
                .orElseGet(() -> {
                    ConceptMastery created = new ConceptMastery(courseId, topic);
                    all.add(created);
                    return created;
                });
    }
}