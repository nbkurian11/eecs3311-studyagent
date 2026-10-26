package studybetter.llm;

import java.util.List;

public interface LLMClient {
    String generate(String prompt);

    default String generateWithContext(String prompt, List<String> context) {
        StringBuilder sb = new StringBuilder("Use the following course material to answer.\n\n");
        for (int i = 0; i < context.size(); i++) {
            sb.append("[Source ").append(i + 1).append("]\n").append(context.get(i)).append("\n\n");
        }
        sb.append("Question/task:\n").append(prompt);
        return generate(sb.toString());
    }
}