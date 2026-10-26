package studybetter;

public class Main {
    public static void main(String[] args) {
        var store = studybetter.persistence.DataStore.getInstance();
        System.out.println("Courses loaded: " + store.getCourses().size());

        store.getCourses().add(new studybetter.domain.Course("EECS3311", "Software Design", "F26"));
        store.saveAll();
        System.out.println("Saved. Courses now: " + store.getCourses().size());

        System.out.println("Same instance? " + (store == studybetter.persistence.DataStore.getInstance()));
    }
}