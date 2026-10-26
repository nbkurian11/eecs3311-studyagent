package studybetter;

public class Main {
    public static void main(String[] args) {
        studybetter.llm.LLMClient llm = studybetter.Config.hasApiKey()
                ? new studybetter.llm.GeminiClient()
                : new studybetter.llm.MockLLMClient();

        System.out.println("Using: " + llm.getClass().getSimpleName());
        try {
            System.out.println(llm.generate("In one sentence, what is the Singleton design pattern?"));
        } catch (studybetter.llm.LLMException e) {
            System.out.println("AI unavailable: " + e.getMessage());
        }
    }
}