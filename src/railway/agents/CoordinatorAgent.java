package railway.agents;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import railway.io.Config;
import railway.io.ReportWriter;
import railway.model.Phase;
import railway.model.Placement;
import railway.model.Snapshot;
import railway.model.Time;
import railway.msg.Msg;
import railway.msg.Msg.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Координатор (сервер). НИЧЕГО не направляет другим агентам – только получает от них информацию,
 * проверяет допустимость, считает критерии, сохраняет initial_plan.json / improved_plan.json /
 * recovery_plan.json и report.html. Агенты после завершения торгов остаются активными.
 */
public class CoordinatorAgent extends Agent {
    private Config cfg;
    private Phase phase = Phase.STAGE1;
    private final Map<String, Placement> plan = new LinkedHashMap<>();
    private final Set<String> unplaced = new TreeSet<>();
    private final Map<Phase, Snapshot> snaps = new EnumMap<>(Phase.class);
    private final List<String> events = new ArrayList<>();
    private long last;
    private boolean dirty;
    private final Path outDir = Path.of("out");
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Override
    protected void setup() {
        cfg = (Config) getArguments()[0];
        Df.register(this, Msg.SVC_COORD, "coordinator");
        addBehaviour(new CyclicBehaviour(this) {
            @Override public void action() {
                ACLMessage m = receive();
                if (m == null) { block(); return; }
                if (Msg.P_REPORT.equals(m.getProtocol())) onReport(m);
            }
        });
        // условие остановки торгов: «тишина» в сообщениях -> сохранить план (агентов не завершаем)
        addBehaviour(new TickerBehaviour(this, 1000) {
            @Override protected void onTick() {
                if (dirty && System.currentTimeMillis() - last > cfg.settings.quietMs) snapshot(phase);
            }
        });
    }

    private void onReport(ACLMessage m) {
        Object o = Msg.get(m, Object.class);
        if (o instanceof Report r) {
            switchPhase(r.phase());
            if (r.placement() == null) { plan.remove(r.cargoId()); unplaced.add(r.cargoId()); }
            else { plan.put(r.cargoId(), r.placement()); unplaced.remove(r.cargoId()); }
            if (r.note() != null) events.add(r.cargoId() + ": " + r.note());
        } else if (o instanceof BreakdownReport b) {
            switchPhase(Phase.RECOVERY);
            events.add("ОТКАЗ вагона " + b.wagonId() + ", ремонт до " + Time.fmt(b.repairUntil()) + ", затронуты " + b.affectedCargo());
            b.affectedCargo().forEach(id -> { plan.remove(id); unplaced.add(id); });
        } else return;
        last = System.currentTimeMillis();
        dirty = true;
    }

    private void switchPhase(Phase p) {
        if (p.ordinal() <= phase.ordinal()) return;
        if (dirty) snapshot(phase);        // зафиксировать завершённую фазу
        phase = p;
    }

    private void snapshot(Phase ph) {
        dirty = false;
        List<String> viol = validate();
        double cost = plan.values().stream().mapToDouble(Placement::cost).sum();
        int late = plan.values().stream().mapToInt(Placement::lateMin).sum();
        Snapshot base = ph == Phase.STAGE2 ? snaps.get(Phase.STAGE1)
                : ph == Phase.RECOVERY ? (snaps.containsKey(Phase.STAGE2) ? snaps.get(Phase.STAGE2) : snaps.get(Phase.STAGE1)) : null;
        String verdict;
        if (!viol.isEmpty()) verdict = "план недопустим";
        else if (base == null) verdict = plan.size() == cfg.cargoes.size() ? "допустимый план, размещено все грузы" : "допустимый, но размещены не все грузы";
        else {
            double rel = base.cost() > 0 ? (base.cost() - cost) / base.cost() : 0;
            String cmp = String.format("стоимость %.0f → %.0f (%.1f %%), опоздание %d → %d мин", base.cost(), cost, rel * 100, base.lateMin(), late);
            verdict = ph == Phase.STAGE2
                    ? (rel > cfg.settings.epsilon ? "улучшение принято: " : "улучшение ≤ ε, не принято: ") + cmp
                    : "план после отказа: " + cmp;   // TODO: при отклонении этапа 2 – откат (агентов уведомлять нельзя, только фиксировать)
        }
        Snapshot s = new Snapshot(ph, new LinkedHashMap<>(plan), new ArrayList<>(unplaced), cost, late, viol, verdict);
        snaps.put(ph, s);
        try {
            Files.createDirectories(outDir);
            String name = switch (ph) { case STAGE1 -> "initial_plan.json"; case STAGE2 -> "improved_plan.json"; case RECOVERY -> "recovery_plan.json"; };
            Files.writeString(outDir.resolve(name), gson.toJson(s));
            ReportWriter.write(outDir.resolve("report.html"), cfg, snaps, events);
        } catch (IOException e) { e.printStackTrace(); }
        System.out.println("[coordinator] " + ph + ": " + verdict + (viol.isEmpty() ? "" : " " + viol));
    }

    /** Проверка допустимости: пересечения по вагону, тяга локомотива, вместимость путей станций. TODO: очистка, сроки окон. */
    private List<String> validate() {
        Set<String> v = new LinkedHashSet<>();
        List<Placement> ps = new ArrayList<>(plan.values());
        for (Placement a : ps) {
            double load = 0; int load1 = 0, load2 = 0;
            for (Placement b : ps) {
                if (a != b && a.wagonId().equals(b.wagonId()) && a.trip().overlaps(b.trip()))
                    v.add("пересечение рейсов вагона " + a.wagonId() + ": " + a.cargoId() + " и " + b.cargoId());
                if (a.locoId().equals(b.locoId()) && a.trip().overlaps(b.trip())) load += b.grossT();
                if (a.from().equals(b.from()) && a.loadWindow().overlaps(b.loadWindow())) load1++;
                if (a.to().equals(b.to()) && a.unloadWindow().overlaps(b.unloadWindow())) load2++;
            }
            for (Config.Locomotive l : cfg.locomotives)
                if (l.id.equals(a.locoId()) && load > l.tractionT + 1e-9)
                    v.add("тяга " + l.id + " превышена в " + a.trip() + ": " + load + " т > " + l.tractionT + " т");
            for (Config.Station s : cfg.stations) {
                if (s.id.equals(a.from()) && load1 > s.capacity) v.add("перегрузка окон погрузки станции " + s.id);
                if (s.id.equals(a.to()) && load2 > s.capacity) v.add("перегрузка окон приёма станции " + s.id);
            }
        }
        return new ArrayList<>(v);
    }
}
