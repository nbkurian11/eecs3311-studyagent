package studybetter.llm;

public class MockLLMClient implements LLMClient {
    private final String cannedResponse;

    public MockLLMClient() {
        this("[Mock LLM] This is a placeholder response used when no API key is configured.");
    }

    public MockLLMClient(String cannedResponse) {
        this.cannedResponse = cannedResponse;
    }

    @Override
    public String generate(String prompt) {
        return cannedResponse;
    }
}