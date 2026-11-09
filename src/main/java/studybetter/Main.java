package studybetter;

import studybetter.controller.AgentController;
import studybetter.ui.CLIApp;
import studybetter.ui.MainWindow;

import java.awt.GraphicsEnvironment;
import java.util.Arrays;

/**
 * Entry point. Builds the {@link AgentController} (Facade) and starts a user interface:
 * the Swing GUI by default, or the command-line interface with {@code --cli}
 * (also used automatically when there is no display).
 */
public class Main {
    public static void main(String[] args) {
        AgentController controller = new AgentController();
        boolean cli = Arrays.asList(args).contains("--cli");
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
}
