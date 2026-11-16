package railway.model;

import java.io.Serializable;

/** Интервал времени [from, to) в минутах. */
public record Slot(int from, int to) implements Serializable {
    public boolean overlaps(Slot o) { return from < o.to && o.from < to; }
    public int len() { return to - from; }
    @Override public String toString() { return Time.fmt(from) + "–" + Time.fmt(to); }
}
