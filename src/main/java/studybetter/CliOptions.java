package studybetter;

import java.nio.file.Path;

/**
 * Parses the command-line options of {@link Main} (for example {@code java -jar Agent.jar -LLMAPI -input task.txt}).
 * Single- and double-dash forms are both accepted ({@code -input} and {@code --input}), and option names are not
 * case-sensitive. A bad option or a missing value throws {@link IllegalArgumentException}; {@code Main} then
 * prints the message and {@link #USAGE}.
 */
public final class CliOptions {

    /** Which LLM to use: AUTO = Gemini when a key is set, otherwise the offline mock. */
    public enum LlmChoice { AUTO, GEMINI, MOCK }

    public static final String USAGE = """
            Usage: java -jar Agent.jar [options]

            With no options the Swing GUI starts (or the CLI when there is no display).

            Options (single or double dash, e.g. -input or --input):
              -h, --help            show this help and exit
              --cli                 start the interactive command-line interface
              -input, --input FILE  run CLI commands from FILE, one command or answer per line, then exit
                                    (implies --cli)
              -LLMAPI, --llm        use the Gemini API; fails if GEMINI_API_KEY is not set
              --mock                use the offline mock LLM (no API calls)
              --model NAME          use this Gemini model for this run (overrides GEMINI_MODEL)

            Without -LLMAPI or --mock, Gemini is used when GEMINI_API_KEY is set (environment variable or .env
            in the current folder), otherwise the offline mock. Data is read from and saved to ./data.

            Example:
              java -jar Agent.jar -LLMAPI -input task.txt""";

    private boolean help;
    private boolean cli;
    private Path inputFile;
    private LlmChoice llm = LlmChoice.AUTO;
    private String model;

    private CliOptions() {}

    public static CliOptions parse(String[] args) {
        CliOptions o = new CliOptions();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("-")) throw new IllegalArgumentException("Unexpected argument: " + arg);
            switch (arg.replaceFirst("^--?", "").toLowerCase()) {
                case "h", "help" -> o.help = true;
                case "cli" -> o.cli = true;
                case "input" -> {
                    o.inputFile = Path.of(valueAfter(args, i++));
                    o.cli = true;
                }
                case "llmapi", "llm" -> o.setLlm(LlmChoice.GEMINI);
                case "mock" -> o.setLlm(LlmChoice.MOCK);
                case "model" -> o.model = valueAfter(args, i++);
                default -> throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }
        return o;
    }

    private static String valueAfter(String[] args, int i) {
        if (i + 1 >= args.length || args[i + 1].startsWith("-")) {
            throw new IllegalArgumentException("Missing value after " + args[i]);
        }
        return args[i + 1];
    }

    private void setLlm(LlmChoice choice) {
        if (llm != LlmChoice.AUTO && llm != choice) {
            throw new IllegalArgumentException("Choose either -LLMAPI or --mock, not both");
        }
        llm = choice;
    }

    public boolean isHelp() { return help; }
    public boolean isCli() { return cli; }
    /** The script file, or null for interactive input. */
    public Path getInputFile() { return inputFile; }
    public LlmChoice getLlm() { return llm; }
    /** The model name given with --model, or null. */
    public String getModel() { return model; }
}
