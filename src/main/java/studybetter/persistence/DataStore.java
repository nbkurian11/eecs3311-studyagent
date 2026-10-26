package studybetter.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import studybetter.domain.Course;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class DataStore {
    private static DataStore instance;

    private final Path filePath;
    private final Gson gson;
    private List<Course> courses = new ArrayList<>();

    private DataStore(Path filePath) {
        this.filePath = filePath;
        this.gson = new GsonBuilder()
                .registerTypeAdapter(LocalDate.class, new LocalDateAdapter())
                .setPrettyPrinting()
                .create();
        loadAll();
    }

    public static synchronized DataStore getInstance() {
        if (instance == null) {
            instance = new DataStore(Path.of("data", "studybetter.json"));
        }
        return instance;
    }

    public List<Course> getCourses() {
        return courses;
    }

    public void saveAll() {
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, gson.toJson(courses));
        } catch (IOException e) {
            System.err.println("Could not save data: " + e.getMessage());
        }
    }

    public void loadAll() {
        if (!Files.exists(filePath)) {
            courses = new ArrayList<>();
            return;
        }
        try {
            Type listType = new TypeToken<ArrayList<Course>>() {}.getType();
            List<Course> loaded = gson.fromJson(Files.readString(filePath), listType);
            courses = (loaded != null) ? loaded : new ArrayList<>();
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not load data, starting fresh: " + e.getMessage());
            courses = new ArrayList<>();
        }
    }
}