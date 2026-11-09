package studybetter.persistence;
import studybetter.domain.ConceptMastery;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import studybetter.domain.Course;
import studybetter.domain.PracticeAssessment;
import studybetter.domain.StudySession;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class DataStore {

    /** Everything the app saves, written to one JSON file. */
    private static class AppData {
        List<Course> courses = new ArrayList<>();
        List<StudySession> sessions = new ArrayList<>();
        List<ConceptMastery> mastery = new ArrayList<>();
        List<PracticeAssessment> practices = new ArrayList<>();

    }

    private static DataStore instance;

    private final Path filePath;
    private final Gson gson;
    private AppData data = new AppData();

    // private constructor: nobody outside can call "new DataStore()"
    private DataStore(Path filePath) {
        this.filePath = filePath;
        this.gson = new GsonBuilder()
                .registerTypeAdapter(LocalDate.class, new LocalDateAdapter())
                .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
                .registerTypeAdapter(PracticeAssessment.class, new PracticeAssessmentAdapter())
                .setPrettyPrinting()
                .create();
        loadAll();
    }

    // the only way to get the DataStore
    public static synchronized DataStore getInstance() {
        if (instance == null) {
            instance = new DataStore(Path.of("data", "studybetter.json"));
        }
        return instance;
    }

    public List<Course> getCourses() { return data.courses; }
    public List<StudySession> getSessions() { return data.sessions; }
    public List<ConceptMastery> getMastery() { return data.mastery; }
    public List<PracticeAssessment> getPractices() { return data.practices; }

    public void saveAll() {
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, gson.toJson(data));
        } catch (IOException e) {
            System.err.println("Could not save data: " + e.getMessage());
        }
    }

    public void loadAll() {
        data = new AppData();
        if (!Files.exists(filePath)) return;
        try {
            String json = Files.readString(filePath).trim();
            if (json.startsWith("[")) {
                // old format from earlier versions: just a list of courses
                Type listType = new TypeToken<ArrayList<Course>>() {}.getType();
                List<Course> courses = gson.fromJson(json, listType);
                if (courses != null) data.courses = courses;
            } else {
                AppData loaded = gson.fromJson(json, AppData.class);
                if (loaded != null) data = loaded;
            }
            if (data.courses == null) data.courses = new ArrayList<>();
            if (data.sessions == null) data.sessions = new ArrayList<>();
            if (data.mastery == null) data.mastery = new ArrayList<>();
            if (data.practices == null) data.practices = new ArrayList<>();
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not load data, starting fresh: " + e.getMessage());
            data = new AppData();
        }
    }
}