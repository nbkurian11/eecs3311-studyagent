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

/**
 * Adapter pattern: adapts the Gemini REST JSON API to the simple {@link LLMClient#generate(String)} call.
 * Builds the JSON request, sends it over HTTP, and pulls the answer text out of the JSON response.
 * Temporary failures (HTTP 429 rate limit, HTTP 503 overloaded) are retried with backoff;
 * other errors (e.g. 400 bad request, 404 unknown model) fail straight away with a short reason.
 */
public class GeminiClient implements LLMClient {
    private static final String BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
    /** Wait before retry 1, 2 and 3. */
    private static final long[] DEFAULT_RETRY_DELAYS_MS = {1000, 2000, 4000};
    private static final int MAX_REASON_CHARS = 200;

    private final String apiKey;
    private final String model;
    private final String endpointTemplate;
    private final long[] retryDelaysMs;
    private final HttpClient http;
    private final Gson gson = new Gson();

    public GeminiClient() {
        this(Config.get("GEMINI_API_KEY", ""), Config.get("GEMINI_MODEL", "gemini-3.8-flash"));
    }

    public GeminiClient(String apiKey, String model) {
        this(apiKey, model, BASE_URL, DEFAULT_RETRY_DELAYS_MS);
    }

    /** Lets tests use a local server ({@code endpointTemplate} contains %s for the model) and short delays. */
    public GeminiClient(String apiKey, String model, String endpointTemplate, long[] retryDelaysMs) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is missing. Copy .env.example to .env and add your key.");
        }
        this.apiKey = apiKey;
        this.model = model;
        this.endpointTemplate = endpointTemplate;
        this.retryDelaysMs = retryDelaysMs.clone();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public String generate(String prompt) {
        HttpRequest request = buildRequest(prompt);
        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> response = send(request);
            int status = response.statusCode();
            if (status == 200) return extractText(response.body());

            boolean temporary = status == 429 || status == 503;
            if (!temporary || attempt >= retryDelaysMs.length) {
                String retried = temporary ? " (after " + attempt + " retries)" : "";
                throw new LLMException("HTTP " + status + ": " + errorReason(response.body()) + retried);
            }
            pause(retryDelaysMs[attempt]);
        }
    }

    private HttpRequest buildRequest(String prompt) {
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

        return HttpRequest.newBuilder()
                .uri(URI.create(String.format(endpointTemplate, model)))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMException("Gemini request was interrupted", e);
        } catch (Exception e) {
            throw new LLMException("Could not reach Gemini: " + e.getMessage(), e);
        }
    }

    private String extractText(String body) {
        try {
            JsonObject json = gson.fromJson(body, JsonObject.class);
            return json.getAsJsonArray("candidates").get(0).getAsJsonObject()
                    .getAsJsonObject("content").getAsJsonArray("parts")
                    .get(0).getAsJsonObject().get("text").getAsString();
        } catch (RuntimeException e) {
            throw new LLMException("Gemini returned an unexpected response (no answer text)", e);
        }
    }

    /** Gemini errors look like {"error":{"code":503,"message":"...","status":"UNAVAILABLE"}}; keep just the message. */
    private String errorReason(String body) {
        String reason = body == null ? "" : body.strip();
        try {
            JsonObject error = gson.fromJson(body, JsonObject.class).getAsJsonObject("error");
            if (error != null && error.has("message")) reason = error.get("message").getAsString().strip();
        } catch (RuntimeException e) {
            // not JSON: fall back to the raw body
        }
        if (reason.isEmpty()) return "no details";
        return reason.length() > MAX_REASON_CHARS ? reason.substring(0, MAX_REASON_CHARS) + "..." : reason;
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMException("Gemini retry was interrupted", e);
        }
    }
}
