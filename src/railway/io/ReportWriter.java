package railway.io;

import railway.model.Phase;
import railway.model.Placement;
import railway.model.Snapshot;
import railway.model.Time;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Результат работы в виде читаемого report.html (по одной таблице на фазу). */
public final class ReportWriter {
    private ReportWriter() {}

    public static void write(Path file, Config cfg, Map<Phase, Snapshot> snaps, List<String> events) throws IOException {
        StringBuilder h = new StringBuilder("<!DOCTYPE html><html lang=\"ru\"><head><meta charset=\"UTF-8\">"
                + "<title>Расписание перевозок</title><style>body{font-family:sans-serif;margin:24px}"
                + "table{border-collapse:collapse;margin:8px 0 20px}td,th{border:1px solid #999;padding:4px 10px}"
                + ".bad{color:#b00}.late{background:#fdd}</style></head><body><h1>Расписание доставки грузов</h1>");
        for (Snapshot s : snaps.values()) {
            h.append("<h2>").append(title(s.phase())).append("</h2><p>").append(esc(s.verdict())).append("</p>")
                    .append("<table><tr><th>Груз</th><th>Вагон</th><th>Локомотив</th><th>Маршрут</th><th>Отправление</th>")
                    .append("<th>Прибытие</th><th>Опоздание, мин</th><th>Очистка</th><th>Стоимость</th></tr>");
            for (Placement p : s.plan().values())
                h.append("<tr").append(p.lateMin() > 0 ? " class=\"late\"" : "").append("><td>").append(esc(p.cargoId()))
                        .append("</td><td>").append(esc(p.wagonId())).append("</td><td>").append(esc(p.locoId()))
                        .append("</td><td>").append(p.from()).append("→").append(p.to())
                        .append("</td><td>").append(Time.fmt(p.trip().from())).append("</td><td>").append(Time.fmt(p.arrive()))
                        .append("</td><td>").append(p.lateMin()).append("</td><td>").append(p.cleaning() == null ? "–" : p.cleaning())
                        .append("</td><td>").append(String.format("%.0f", p.cost())).append("</td></tr>");
            h.append("</table><p>Итого: стоимость <b>").append(String.format("%.0f", s.cost())).append("</b>, опоздание <b>")
                    .append(s.lateMin()).append("</b> мин");
            if (!s.unplaced().isEmpty()) h.append(", <span class=\"bad\">не размещены: ").append(esc(s.unplaced().toString())).append("</span>");
            for (String v : s.violations()) h.append("<br><span class=\"bad\">нарушение: ").append(esc(v)).append("</span>");
            h.append("</p>");
        }
        if (!events.isEmpty()) {
            h.append("<h2>События</h2><ul>");
            for (String e : events) h.append("<li>").append(esc(e)).append("</li>");
            h.append("</ul>");
        }
        Files.writeString(file, h.append("</body></html>").toString());
    }

    private static String title(Phase p) {
        return switch (p) {
            case STAGE1 -> "Этап 1 – допустимый план";
            case STAGE2 -> "Этап 2 – после ребалансировки";
            case RECOVERY -> "План после отказа вагона";
        };
    }

    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
