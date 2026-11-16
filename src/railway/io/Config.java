package railway.io;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Исходные данные (data/input.json). Один и тот же файл читают все процессы,
 * но каждый агент берёт из него только свою запись.
 */
public class Config {

    public static class Settings {
        public int prepMin = 120;        // от готовности груза до отправления (подача + погрузка)
        public int loadMin = 60;         // окно погрузки / длительность погрузки
        public int unloadMin = 60;       // окно приёма
        public int cleaningMin = 60;     // очистка вагона при смене класса груза
        public long negotiationTimeoutMs = 3000;
        public long holdTtlMs = 5000;    // время жизни временной блокировки интервала у вагона
        public long stage2DelayMs = 6000;// пауза перед ребалансировкой у груза с опозданием
        public long quietMs = 5000;      // «тишина», после которой координатор сохраняет план
        public long replanMsPerSlackHour = 300; // срочные грузы после отказа ищут замену раньше
        public int maxRetries = 3;
        public int maxSwapRounds = 3;
        public double epsilon = 0.05;    // порог относительного улучшения (доля) для принятия плана
        public boolean allowCrossWagonSwap = false; // TODO: обмен между разными вагонами
    }

    public static class Costs {
        public double emptyKmRate = 0.75;  // за км порожнего пробега
        public double latePerHour = 25;    // штраф за час опоздания
    }

    public static class Station { public String id, name; public int capacity = 1; }
    public static class Link {
        public String a, b; public double km; public int tripMin, turnaroundMin; public double tripCost;
    }
    public static class Locomotive { public String id, station; public double tractionT; }
    public static class Wagon {
        public String id, type, home; public double capacityT, tareT;
    }
    public static class Cargo {
        public String id, name, cargoClass, from, to, ready, deadline; public double weightT;
    }
    /** Незапланированная ситуация: отказ вагона через atMs мс после старта (реальное время). */
    public static class Failure { public String wagon, repairUntil; public long atMs; }

    public Settings settings = new Settings();
    public Costs costs = new Costs();
    public List<Station> stations = new ArrayList<>();
    public List<Link> links = new ArrayList<>();
    public List<Locomotive> locomotives = new ArrayList<>();
    public List<Wagon> wagons = new ArrayList<>();
    public List<Cargo> cargoes = new ArrayList<>();
    public List<Failure> failures = new ArrayList<>();
    public Map<String, List<String>> compatibility = Map.of(); // тип вагона -> допустимые классы грузов

    public static Config load(String path) throws IOException {
        return new Gson().fromJson(Files.readString(Path.of(path)), Config.class);
    }

    public Link link(String x, String y) {
        for (Link l : links)
            if ((l.a.equals(x) && l.b.equals(y)) || (l.a.equals(y) && l.b.equals(x))) return l;
        return null;
    }

    public double km(String x, String y) {
        if (x.equals(y)) return 0;
        Link l = link(x, y);
        return l == null ? 0 : l.km;
    }

    public boolean compatible(String wagonType, String cargoClass) {
        return compatibility.getOrDefault(wagonType, List.of()).contains(cargoClass);
    }

    public Locomotive locoAt(String station) {
        return locomotives.stream().filter(l -> l.station.equals(station)).findFirst().orElse(null);
    }
}
