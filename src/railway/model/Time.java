package railway.model;

/** Время в минутах от начала суток. Значения >= 1440 означают следующие сутки. */
public final class Time {
    private Time() {}

    public static int parse(String hhmm) {
        String[] p = hhmm.trim().split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    public static String fmt(int min) {
        return String.format("%02d:%02d%s", (min / 60) % 24, min % 60, min >= 1440 ? "(+1д)" : "");
    }
}
