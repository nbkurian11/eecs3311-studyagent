package studybetter;

public class Main {
    public static void main(String[] args) {
        studybetter.domain.Course c = new studybetter.domain.Course("eecs3311", "Software Design", "F26");
        c.addItem(new studybetter.domain.AssessmentItem("Midterm", java.time.LocalDate.of(2026, 10, 26), 20));
        System.out.println(c + " has " + c.getItems().size() + " assessment(s)");
    }
}