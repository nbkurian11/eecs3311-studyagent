package studybetter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class Config {
    private static final Map<String, String> values = new HashMap<>();

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

    public static String get(String key, String defaultValue) {
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        return values.getOrDefault(key, defaultValue);
    }

    public static boolean hasApiKey() {
        String key = get("GEMINI_API_KEY", "");
        return !key.isBlank() && !key.equals("YOUR_LOCAL_API_KEY_HERE");
    }
}