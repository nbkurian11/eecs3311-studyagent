package studybetter.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the tools available to the {@link StudyAgent}, keyed by name.
 * The agent asks the registry for a tool instead of creating tools itself, so new tools
 * can be added without changing the agent's constructor.
 */
public class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool tool) {
        if (tool == null) throw new IllegalArgumentException("Tool is required");
        if (tools.containsKey(tool.getName())) {
            throw new IllegalArgumentException("A tool named " + tool.getName() + " is already registered");
        }
        tools.put(tool.getName(), tool);
    }

    public Tool get(String name) {
        Tool tool = tools.get(name);
        if (tool == null) throw new IllegalStateException("No tool named " + name + " is registered");
        return tool;
    }

    /** Looks a tool up and checks it has the expected type, so the agent can use its typed methods. */
    public <T extends Tool> T get(String name, Class<T> type) {
        Tool tool = get(name);
        if (!type.isInstance(tool)) {
            throw new IllegalStateException("Tool " + name + " is not a " + type.getSimpleName());
        }
        return type.cast(tool);
    }

    public List<Tool> listTools() {
        return new ArrayList<>(tools.values());
    }
}
