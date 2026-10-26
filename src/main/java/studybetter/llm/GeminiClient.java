package studybetter.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import studybetter.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class GeminiClient implements LLMClient {
    private static final String BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    private final String apiKey;
    private final String model;
    private final HttpClient http;
    private final Gson gson = new Gson();

    public GeminiClient() {
        this(Config.get("GEMINI_API_KEY", ""), Config.get("GEMINI_MODEL", "gemini-2.5-flash"));
    }

    public GeminiClient(String apiKey, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is missing. Copy .env.example to .env and add your key.");
        }
        this.apiKey = apiKey;
        this.model = model;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public String generate(String prompt) {
        JsonObject part = new JsonObject();
        part.addProperty("text", prompt);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject content = new JsonObject();
        content.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(content);
        JsonObject body = new JsonObject();
        body.add("contents", contents);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format(BASE_URL, model)))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new LLMException("Gemini returned HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonObject json = gson.fromJson(response.body(), JsonObject.class);
            return json.getAsJsonArray("candidates").get(0).getAsJsonObject()
                    .getAsJsonObject("content").getAsJsonArray("parts")
                    .get(0).getAsJsonObject().get("text").getAsString();
        } catch (LLMException e) {
            throw e;
        } catch (Exception e) {
            throw new LLMException("Could not reach Gemini: " + e.getMessage(), e);
        }
    }
}