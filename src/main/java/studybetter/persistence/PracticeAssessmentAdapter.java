package studybetter.persistence;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import studybetter.domain.MockExam;
import studybetter.domain.PracticeAssessment;
import studybetter.domain.Quiz;

import java.lang.reflect.Type;

/**
 * Gson cannot create an abstract {@link PracticeAssessment} when loading, so this adapter reads the saved
 * "type" field and loads a {@link Quiz} or {@link MockExam}. Saving writes the real subclass's fields.
 */
public class PracticeAssessmentAdapter
        implements JsonSerializer<PracticeAssessment>, JsonDeserializer<PracticeAssessment> {

    @Override
    public JsonElement serialize(PracticeAssessment src, Type typeOfSrc, JsonSerializationContext context) {
        return context.serialize(src, src.getClass());
    }

    @Override
    public PracticeAssessment deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) {
        JsonElement type = json.getAsJsonObject().get("type");
        String name = type == null || type.isJsonNull() ? "" : type.getAsString();
        return switch (name) {
            case Quiz.TYPE -> context.deserialize(json, Quiz.class);
            case MockExam.TYPE -> context.deserialize(json, MockExam.class);
            default -> throw new JsonParseException("Unknown practice type '" + name + "'");
        };
    }
}
