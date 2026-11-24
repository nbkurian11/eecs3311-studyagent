package studybetter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads settings such as GEMINI_API_KEY and GEMINI_MODEL. Lookup order: runtime overrides (set from command-line
 * options such as {@code --model} or {@code --mock}), then environment variables, then the {@code .env} file in
 * the current folder.
 */
public final class Config {
    private static final Map<String, String> values = new HashMap<>();
    private static final Map<String, String> overrides = new HashMap<>();

    static {
        Path env = Path.of(".env");
        if (Files.exists(env)) {
            try {
                for (String line : Files.readAllLines(env)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
                    int i = line.indexOf('=');
                    values.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
                }
            } catch (IOException e) {
                System.err.println("Could not read .env: " + e.getMessage());
            }
        }
    }

    private Config() {}

    /** Sets a value for this run only; it wins over environment variables and .env. */
    public static void override(String key, String value) {
        overrides.put(key, value);
    }

    public static String get(String key, String defaultValue) {
        if (overrides.containsKey(key)) return overrides.get(key);
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        return values.getOrDefault(key, defaultValue);
    }

    public static boolean hasApiKey() {
        String key = get("GEMINI_API_KEY", "");
        return !key.isBlank() && !key.equals("YOUR_LOCAL_API_KEY_HERE");
    }
}
