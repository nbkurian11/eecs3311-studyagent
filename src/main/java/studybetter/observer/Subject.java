package studybetter.observer;

import java.util.ArrayList;
import java.util.List;

public abstract class Subject {
    private final List<DataObserver> observers = new ArrayList<>();

    public void attach(DataObserver observer) {
        if (!observers.contains(observer)) observers.add(observer);
    }

    public void detach(DataObserver observer) {
        observers.remove(observer);
    }

    protected void notifyObservers(String event) {
        for (DataObserver o : new ArrayList<>(observers)) {
            o.onDataChanged(event);
        }
    }
}
