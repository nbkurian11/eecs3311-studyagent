package studybetter.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import studybetter.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * Adapter pattern: adapts the Gemini REST JSON API to the simple {@link LLMClient#generate(String)} call.
 * Builds the JSON request, sends it over HTTP, and pulls the answer text out of the JSON response.
 * Temporary failures (HTTP 429 rate limit, HTTP 503 overloaded) are retried with backoff, and a request
 * that times out (the model is often just overloaded) is retried once; other errors (e.g. 400 bad request,
 * 404 unknown model) fail straight away with a short reason.
 * A lower thinking level (GEMINI_THINKING_LEVEL, default "low") makes replies faster; if the model rejects
 * the level, the request is sent once more without it.
 */
public class GeminiClient implements LLMClient {
    private static final String BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
    /** Wait before retry 1, 2 and 3. */
    private static final long[] DEFAULT_RETRY_DELAYS_MS = {1000, 2000, 4000};
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final String DEFAULT_THINKING_LEVEL = "low";
    private static final int MAX_TIMEOUT_RETRIES = 1;
    private static final int MAX_REASON_CHARS = 200;

    private final String apiKey;
    private final String model;
    private final String endpointTemplate;
    private final long[] retryDelaysMs;
    private final Duration requestTimeout;
    /** Null or blank means: do not send a thinking setting (use the model's default). */
    private final String thinkingLevel;
    private final HttpClient http;
    private final Gson gson = new Gson();

    public GeminiClient() {
        this(Config.get("GEMINI_API_KEY", ""), Config.get("GEMINI_MODEL", "gemini-3.8-flash"), BASE_URL,
                DEFAULT_RETRY_DELAYS_MS, DEFAULT_REQUEST_TIMEOUT,
                Config.get("GEMINI_THINKING_LEVEL", DEFAULT_THINKING_LEVEL));
    }

    public GeminiClient(String apiKey, String model) {
        this(apiKey, model, BASE_URL, DEFAULT_RETRY_DELAYS_MS);
    }

    /** Lets tests use a local server ({@code endpointTemplate} contains %s for the model) and short delays. */
    public GeminiClient(String apiKey, String model, String endpointTemplate, long[] retryDelaysMs) {
        this(apiKey, model, endpointTemplate, retryDelaysMs, DEFAULT_REQUEST_TIMEOUT, DEFAULT_THINKING_LEVEL);
    }

    /** Full constructor; tests use it for a short request timeout. */
    public GeminiClient(String apiKey, String model, String endpointTemplate, long[] retryDelaysMs,
                        Duration requestTimeout, String thinkingLevel) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is missing. Copy .env.example to .env and add your key.");
        }
        this.apiKey = apiKey;
        this.model = model;
        this.endpointTemplate = endpointTemplate;
        this.retryDelaysMs = retryDelaysMs.clone();
        this.requestTimeout = requestTimeout;
        this.thinkingLevel = thinkingLevel == null || thinkingLevel.isBlank() ? null : thinkingLevel.trim();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public String generate(String prompt) {
        boolean useThinking = thinkingLevel != null;
        int busyRetries = 0;
        int timeoutRetries = 0;
        while (true) {
            HttpResponse<String> response;
            try {
                response = send(buildRequest(prompt, useThinking));
            } catch (HttpTimeoutException e) {
                // a timeout usually means the model is overloaded, like a 503: retry once
                if (timeoutRetries >= MAX_TIMEOUT_RETRIES) {
                    throw new LLMException("Could not reach Gemini: request timed out after "
                            + requestTimeout.toSeconds() + "s (after " + timeoutRetries
                            + " retry). The model may be overloaded; try again later.", e);
                }
                timeoutRetries++;
                pause(retryDelaysMs.length > 0 ? retryDelaysMs[0] : 0);
                continue;
            }
            int status = response.statusCode();
            if (status == 200) return extractText(response.body());

            String reason = errorReason(response.body());
            if (status == 400 && useThinking && reason.toLowerCase().contains("thinking")) {
                // this model does not accept the configured thinking level: use its default instead
                useThinking = false;
                continue;
            }
            boolean temporary = status == 429 || status == 503;
            if (!temporary || busyRetries >= retryDelaysMs.length) {
                String retried = temporary ? " (after " + busyRetries + " retries)" : "";
                throw new LLMException("HTTP " + status + ": " + reason + retried);
            }
            pause(retryDelaysMs[busyRetries]);
            busyRetries++;
        }
    }

    private HttpRequest buildRequest(String prompt, boolean useThinking) {
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
        if (useThinking) {
            // {"generationConfig":{"thinkingConfig":{"thinkingLevel":"low"}}}
            JsonObject thinking = new JsonObject();
            thinking.addProperty("thinkingLevel", thinkingLevel);
            JsonObject generationConfig = new JsonObject();
            generationConfig.add("thinkingConfig", thinking);
            body.add("generationConfig", generationConfig);
        }

        return HttpRequest.newBuilder()
                .uri(URI.create(String.format(endpointTemplate, model)))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();
    }

    /** Sends the request; a timeout is thrown separately so {@link #generate} can retry it. */
    private HttpResponse<String> send(HttpRequest request) throws HttpTimeoutException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw e;
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
