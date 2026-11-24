package studybetter;

import studybetter.controller.AgentController;
import studybetter.ui.CLIApp;
import studybetter.ui.MainWindow;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * Entry point. Reads the command-line options ({@link CliOptions}), applies the LLM choices to {@link Config},
 * builds the {@link AgentController} (Facade) and starts a user interface: the Swing GUI by default, the
 * interactive CLI with {@code --cli} (also used when there is no display), or the CLI reading a script with
 * {@code -input FILE}.
 * Exit codes: 0 = ok, 1 = missing input file or API key, 2 = bad command-line options.
 */
public class Main {
    public static void main(String[] args) {
        CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            System.err.println(CliOptions.USAGE);
            System.exit(2);
            return;
        }
        if (options.isHelp()) {
            System.out.println(CliOptions.USAGE);
            return;
        }

        if (options.getModel() != null) Config.override("GEMINI_MODEL", options.getModel());
        switch (options.getLlm()) {
            case MOCK -> Config.override("GEMINI_API_KEY", "");
            case GEMINI -> {
                if (!Config.hasApiKey()) {
                    System.err.println("Error: -LLMAPI needs a Gemini API key. Set GEMINI_API_KEY as an environment "
                            + "variable or in a .env file in the current folder, or use --mock.");
                    System.exit(1);
                }
            }
            case AUTO -> { }
        }

        if (options.getInputFile() != null) {
            runScript(options);
            return;
        }

        AgentController controller = new AgentController();
        boolean cli = options.isCli();
        if (!cli && GraphicsEnvironment.isHeadless()) {
            System.out.println("No display available, starting the CLI instead.");
            cli = true;
        }
        if (cli) {
            new CLIApp(controller).run();
        } else {
            MainWindow.launch(controller);
        }
    }

    /** Runs the CLI with its input read from the script file, then exits. */
    private static void runScript(CliOptions options) {
        if (!Files.isRegularFile(options.getInputFile()) || !Files.isReadable(options.getInputFile())) {
            System.err.println("Input file not found: " + options.getInputFile());
            System.exit(1);
        }
        try (InputStream script = Files.newInputStream(options.getInputFile())) {
            new CLIApp(new AgentController(), script, true).run();
        } catch (IOException e) {
            System.err.println("Input file not found: " + options.getInputFile());
            System.exit(1);
        }
        System.exit(0);
    }
}
