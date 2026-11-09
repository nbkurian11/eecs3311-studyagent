package studybetter;

import studybetter.controller.AgentController;
import studybetter.ui.CLIApp;

import java.util.Arrays;

/**
 * Entry point. Builds the {@link AgentController} (Facade) and starts the user interface.
 * For now the CLI is the default; {@code --gui} is reserved for the Swing window, which is not built yet.
 */
public class Main {
    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--gui")) {
            System.out.println("GUI not built yet");
            return;
        }
        new CLIApp(new AgentController()).run();
    }
}
