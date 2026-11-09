package studybetter.agent;

/**
 * A capability the {@link StudyAgent} can call to gather information before asking the LLM
 * (tool use). Each tool has a unique name so it can be looked up in the {@link ToolRegistry}.
 */
public interface Tool {
    /** Unique name used to look the tool up, e.g. "material-search". */
    String getName();

    /** One line describing what the tool does and what input it expects. */
    String getDescription();

    /** Runs the tool on a text input and returns a text result. */
    String execute(String input);
}
