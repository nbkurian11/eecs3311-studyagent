package studybetter;

public class Main {
    public static void main(String[] args) {
        boolean cli = args.length > 0 && args[0].equals("--cli");
        System.out.println("StudyBetter starting in " + (cli ? "CLI" : "GUI") + " mode");
        System.out.println("Gemini API key configured: " + Config.hasApiKey());
    }
}