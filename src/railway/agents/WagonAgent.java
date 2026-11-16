package railway.agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.lang.acl.ACLMessage;
import railway.io.Config;
import railway.model.Placement;
import railway.model.Slot;
import railway.model.Time;
import railway.msg.Msg;
import railway.msg.Msg.*;

import java.util.*;

/**
 * Агент вагона (клиент). СТОИМОСТЬ ПЕРЕВОЗКИ СЧИТАЕТ ВАГОН:
 *   тариф рейса + порожний пробег * ставка + штраф за опоздание * ставка.
 * Вагон: проверяет тип/вес/совместимость/очистку, спрашивает тягу у локомотива,
 * временно блокирует интервал на время переговоров, бронирует, обменивает брони (этап 2),
 * при отказе отменяет брони и уведомляет грузы и координатора.
 */
public class WagonAgent extends Agent {

    private static class Booking {
        Msg.CargoInfo cargo; AID cargoAid; Slot trip; int tripMin; String locoId; double base; Slot cleaning;
    }
    private static class Hold { Slot slot; String cls; long expires; }
    private static class Ctx {
        ACLMessage cfp; AID cargoAid, locoAid; Msg.CargoInfo cargo; Config.Link link;
        double emptyKm, gross; TransportOffer offer;
        int expected, replies; TractionOffer best; AID bestAid;   // ответы всех подходящих локомотивов
    }
    private static class SwapCtx { Booking a, b; Msg.CargoInfo ca, cb; AID aa, ab; Slot cleanA, cleanB; ACLMessage request; }
    private static class CrossCtx { String key; Booking a; ACLMessage request; PrepareOk ok; AID partnerWagon; }

    private Config cfg;
    private Config.Wagon me;
    private boolean broken;
    private int repairUntil;
    private final List<Booking> bookings = new ArrayList<>();
    private final Map<String, Hold> holds = new HashMap<>();
    private final Map<String, Ctx> ctx = new HashMap<>();
    private final Map<String, SwapCtx> swaps = new HashMap<>();
    private final Map<String, CrossCtx> cross = new HashMap<>();
    private final Map<String, Long> locks = new HashMap<>();   // брони, занятые незавершённым обменом между вагонами

    private boolean locked(String cargoId) {
        long now = System.currentTimeMillis();
        locks.values().removeIf(t -> t < now);
        return locks.containsKey(cargoId);
    }

    private void lock(String cargoId) { locks.put(cargoId, System.currentTimeMillis() + 2 * cfg.settings.holdTtlMs); }

    private void endCross(CrossCtx x) { cross.remove(x.key); cross.remove(x.key + "/loco"); locks.remove(x.a.cargo.id()); }

    @Override
    protected void setup() {
        cfg = (Config) getArguments()[0];
        me = (Config.Wagon) getArguments()[1];
        Df.register(this, Msg.SVC_WAGON, me.id);
        setEnabledO2ACommunication(true, 0);   // ручной ввод отказа из консоли клиента

        // отказы, заданные в input.json
        for (Config.Failure f : cfg.failures)
            if (f.wagon.equals(me.id))
                addBehaviour(new WakerBehaviour(this, f.atMs) {
                    @Override protected void onWake() { breakdown(Time.parse(f.repairUntil)); }
                });

        addBehaviour(new CyclicBehaviour(this) {
            @Override public void action() {
                Object o = getO2AObject();
                if (o instanceof FailCommand fc) breakdown(Time.parse(fc.repairUntil()));
                long now = System.currentTimeMillis();
                holds.values().removeIf(h -> h.expires < now);
                ACLMessage m = receive();
                if (m == null) { block(200); return; }
                handleMessage(m);
            }
        });
    }

    private void log(String s) { System.out.println("[" + getLocalName() + "] " + s); }

    private void handleMessage(ACLMessage m) {
        String p = m.getProtocol();
        if (p == null) return;
        switch (p) {
            case Msg.P_TRANSPORT -> {
                switch (m.getPerformative()) {
                    case ACLMessage.CFP -> onCfp(m);
                    case ACLMessage.ACCEPT_PROPOSAL -> onAccept(m);
                    case ACLMessage.REJECT_PROPOSAL -> { holds.remove(m.getConversationId()); ctx.remove(m.getConversationId()); }
                    default -> {}
                }
            }
            case Msg.P_TRACTION -> onTraction(m);
            case Msg.P_BOOK_LOCO -> onLocoBooked(m);
            case Msg.P_SWAP_EXEC -> onSwapExec(m);
            default -> {}
        }
    }

    // ===================== Этап 1: запрос на перевозку =====================

    private void onCfp(ACLMessage m) {
        TransportRequest req = Msg.get(m, TransportRequest.class);
        Msg.CargoInfo c = req.cargo();
        if (broken && !req.allowAfterRepair()) { refuse(m, "BROKEN"); return; }
        if (!cfg.compatible(me.type, c.cargoClass()) || c.weightT() > me.capacityT) { refuse(m, "INCOMPATIBLE"); return; }
        Config.Link link = cfg.link(c.from(), c.to());
        List<Config.Locomotive> locos = new ArrayList<>();             // все локомотивы станции отправления
        for (Config.Locomotive l : cfg.locomotives) if (l.station.equals(c.from())) locos.add(l);
        if (link == null || locos.isEmpty()) { refuse(m, "NO_ROUTE"); return; }

        int nb = broken ? Math.max(req.notBefore(), repairUntil) : req.notBefore();
        int start = findStart(c, nb, link.turnaroundMin);
        Slot trip = new Slot(start, start + link.turnaroundMin);
        if (overlapsHold(trip)) { refuse(m, "BLOCKED"); return; }   // интервал временно занят другим грузом

        Ctx x = new Ctx();
        x.cfp = m; x.cargo = c; x.link = link; x.cargoAid = m.getSender(); x.gross = c.weightT() + me.tareT;
        ctx.put(m.getConversationId(), x);

        ACLMessage q = new ACLMessage(ACLMessage.CFP);
        q.setProtocol(Msg.P_TRACTION);
        q.setConversationId(m.getConversationId());
        for (Config.Locomotive l : locos) {
            AID a = Df.find(this, Msg.SVC_LOCO, l.id);
            if (a != null) { q.addReceiver(a); x.expected++; }
        }
        if (x.expected == 0) { ctx.remove(m.getConversationId()); refuse(m, "NO_LOCO"); return; }
        Msg.put(q, new TractionRequest(me.id, x.gross, start, link.turnaroundMin));
        send(q);
    }

    /** Ждём ответы всех локомотивов, берём предложение с самым ранним стартом. */
    private void onTraction(ACLMessage m) {
        Ctx x = ctx.get(m.getConversationId());
        if (x == null || x.offer != null) return;
        if (m.getPerformative() == ACLMessage.PROPOSE) {
            TractionOffer o = Msg.get(m, TractionOffer.class);
            if (x.best == null || o.slot().from() < x.best.slot().from()) { x.best = o; x.bestAid = m.getSender(); }
        } else if (m.getPerformative() != ACLMessage.REFUSE) return;
        if (++x.replies < x.expected) return;

        String conv = m.getConversationId();
        if (x.best == null) { ctx.remove(conv); refuse(x.cfp, "NO_TRACTION"); return; }
        Slot trip = x.best.slot();
        String cls = x.cargo.cargoClass();
        if (!fitsBookings(trip, x.cargo, null) || overlapsHold(trip)) {   // локомотив сдвинул слот – перепроверка
            ctx.remove(conv);
            refuse(x.cfp, overlapsHold(trip) ? "BLOCKED" : "CONFLICT");   // BLOCKED – временно, груз повторит запрос
            return;
        }
        Hold h = new Hold();                                              // блок на время переговоров
        h.slot = trip; h.cls = cls; h.expires = System.currentTimeMillis() + cfg.settings.holdTtlMs;
        holds.put(conv, h);

        x.emptyKm = cfg.km(locationBefore(trip.from()), x.cargo.from());  // порожний пробег от места предыдущего рейса
        int arrive = trip.from() + x.link.tripMin;
        int late = Math.max(0, arrive - x.cargo.deadlineMin());
        double base = x.link.tripCost + cfg.costs.emptyKmRate * x.emptyKm;
        double pen = cfg.costs.latePerHour * late / 60.0;
        x.locoAid = x.bestAid;
        x.offer = new TransportOffer(me.id, x.best.locoId(), trip, arrive, late, base, pen, x.emptyKm, cleaningBefore(trip.from(), cls, null));

        ACLMessage rep = x.cfp.createReply();
        rep.setPerformative(ACLMessage.PROPOSE);
        Msg.put(rep, x.offer);
        send(rep);
    }

    private void onAccept(ACLMessage m) {
        Ctx x = ctx.get(m.getConversationId());
        if (x == null || x.offer == null) {
            ACLMessage f = m.createReply(); f.setPerformative(ACLMessage.FAILURE); f.setContent("NO_OFFER"); send(f); return;
        }
        ACLMessage b = new ACLMessage(ACLMessage.REQUEST);
        b.setProtocol(Msg.P_BOOK_LOCO);
        b.setConversationId(m.getConversationId());
        b.addReceiver(x.locoAid);
        Msg.put(b, new LocoBook(me.id, x.offer.trip(), x.gross));
        send(b);
    }

    private void onLocoBooked(ACLMessage m) {
        String conv = m.getConversationId();
        if (swaps.containsKey(conv)) { onSwapLoco(m, swaps.remove(conv)); return; }
        if (cross.containsKey(conv)) { onCrossLoco(m, cross.get(conv)); return; }
        Ctx x = ctx.remove(conv);
        if (x == null) return;                // например, подтверждение отмены брони
        holds.remove(conv);
        ACLMessage rep = new ACLMessage(m.getPerformative() == ACLMessage.INFORM ? ACLMessage.INFORM : ACLMessage.FAILURE);
        rep.setProtocol(Msg.P_TRANSPORT);
        rep.setConversationId(conv);
        rep.addReceiver(x.cargoAid);
        if (m.getPerformative() == ACLMessage.INFORM) {
            Booking b = new Booking();
            b.cargo = x.cargo; b.cargoAid = x.cargoAid; b.trip = x.offer.trip(); b.tripMin = x.link.tripMin;
            b.locoId = x.offer.locoId(); b.base = x.offer.baseCost(); b.cleaning = x.offer.cleaning();
            bookings.add(b);
            Msg.put(rep, placementOf(b));
            log("бронь " + x.cargo.id() + " " + b.trip);
        } else {
            rep.setContent("LOCO_BUSY");      // груз повторит запрос (локомотив предложит другое время)
        }
        send(rep);
    }

    // ===================== Проверки вагона =====================

    /** Время между рейсами: порожний пробег до станции погрузки + очистка при смене класса + погрузка. */
    private int gap(Msg.CargoInfo p, Msg.CargoInfo n) {
        return cfg.settings.loadMin + (p.cargoClass().equals(n.cargoClass()) ? 0 : cfg.settings.cleaningMin) + emptyMin(p.from(), n.from());
    }

    private int emptyMin(String from, String to) {
        if (from.equals(to)) return 0;
        Config.Link l = cfg.link(from, to);
        return l == null ? 0 : l.tripMin;
    }

    /** Где вагон перед рейсом, начинающимся в start: станция отправления предыдущего рейса или домашняя. */
    private String locationBefore(int start) {
        Booking prev = null;
        for (Booking b : bookings) if (b.trip.to() <= start && (prev == null || b.trip.to() > prev.trip.to())) prev = b;
        return prev == null ? me.home : prev.cargo.from();
    }

    private int findStart(Msg.CargoInfo c, int nb, int dur) {
        int base = Math.max(nb, c.readyMin() + cfg.settings.prepMin);
        base = Math.max(base, cfg.settings.dayStartMin + emptyMin(me.home, c.from()) + cfg.settings.loadMin);   // вагон должен дойти
        List<Integer> cand = new ArrayList<>(List.of(base));
        for (Booking b : bookings) cand.add(Math.max(base, b.trip.to() + gap(b.cargo, c)));
        Collections.sort(cand);
        for (int s : cand) if (fitsBookings(new Slot(s, s + dur), c, null)) return s;
        return cand.get(cand.size() - 1);
    }

    /** Нет пересечений с бронями; между рейсами хватает времени на погрузку и очистку при смене класса груза. */
    private boolean fitsBookings(Slot t, Msg.CargoInfo c, String ignoreCargo) {
        for (Booking b : bookings) {
            if (b.cargo.id().equals(ignoreCargo)) continue;
            if (t.from() >= b.trip.to()) { if (t.from() < b.trip.to() + gap(b.cargo, c)) return false; }
            else if (b.trip.from() >= t.to()) { if (b.trip.from() < t.to() + gap(c, b.cargo)) return false; }
            else return false;
        }
        return true;
    }

    private boolean overlapsHold(Slot t) {
        for (Hold h : holds.values()) if (h.slot.overlaps(t)) return true;
        return false;
    }

    /** Очистка нужна, если предыдущий рейс вагона был с другим классом груза. */
    private Slot cleaningBefore(int start, String cls, String ignoreCargo) {
        Booking prev = null;
        for (Booking b : bookings)
            if (!b.cargo.id().equals(ignoreCargo) && b.trip.to() <= start && (prev == null || b.trip.to() > prev.trip.to())) prev = b;
        if (prev == null || prev.cargo.cargoClass().equals(cls)) return null;
        return new Slot(prev.trip.to(), prev.trip.to() + cfg.settings.cleaningMin);
    }

    private Placement placementOf(Booking b) {
        int arrive = b.trip.from() + b.tripMin;
        int late = Math.max(0, arrive - b.cargo.deadlineMin());
        return new Placement(b.cargo.id(), me.id, b.locoId, b.cargo.from(), b.cargo.to(), b.trip, arrive, late,
                b.base, cfg.costs.latePerHour * late / 60.0, b.cargo.weightT() + me.tareT, b.cleaning,
                new Slot(b.trip.from() - cfg.settings.loadMin, b.trip.from()),
                new Slot(arrive, arrive + cfg.settings.unloadMin));
    }

    private void refuse(ACLMessage cfp, String reason) {
        ACLMessage r = cfp.createReply();
        r.setPerformative(ACLMessage.REFUSE);
        r.setContent(reason);
        send(r);
    }

    private Booking find(String cargoId) {
        return bookings.stream().filter(b -> b.cargo.id().equals(cargoId)).findFirst().orElse(null);
    }

    // ===================== Этап 2: обмен рейсами (внутри вагона и между вагонами) =====================
    // Слот (время, локомотив, базовая стоимость) принадлежит вагону; при обмене меняются ГРУЗЫ в слотах.

    private double gross(Msg.CargoInfo c) { return c.weightT() + me.tareT; }
    private boolean ready(Booking bk) { return bk.trip.from() >= bk.cargo.readyMin() + cfg.settings.prepMin; }

    /** Можно ли поставить груз c в слот bk (совместимость, вес, готовность, очистка между рейсами). */
    private boolean acceptable(Booking bk, Msg.CargoInfo c) {
        if (!cfg.compatible(me.type, c.cargoClass()) || c.weightT() > me.capacityT) return false;
        Msg.CargoInfo old = bk.cargo;
        bk.cargo = c;
        boolean ok = ready(bk) && sequenceValid();
        bk.cargo = old;
        return ok;
    }

    private void sendMoves(String conv, String locoId, List<LocoMove> mv) {
        ACLMessage q = new ACLMessage(ACLMessage.REQUEST);
        q.setProtocol(Msg.P_BOOK_LOCO);
        q.setConversationId(conv);
        q.addReceiver(Df.find(this, Msg.SVC_LOCO, locoId));
        Msg.put(q, new LocoMoves(mv));
        send(q);
    }

    private void notifySwap(AID to, String conv, SwapDone d) {
        ACLMessage n = new ACLMessage(ACLMessage.INFORM);
        n.setProtocol(Msg.P_SWAP_EXEC);
        n.setConversationId(conv);
        n.addReceiver(to);
        Msg.put(n, d);
        send(n);
    }

    private void onSwapExec(ACLMessage m) {
        int perf = m.getPerformative();
        if (perf == ACLMessage.FAILURE) {                       // партнёрский вагон отказал на фазе 1
            CrossCtx x = cross.get(m.getConversationId());
            if (x != null) { endCross(x); fail(x.request, "PARTNER_WAGON_REFUSED"); }
            return;
        }
        Object o = Msg.get(m, Object.class);
        if (o instanceof SwapExec e && perf == ACLMessage.REQUEST) startSwap(m, e);
        else if (o instanceof CrossPrepare c && perf == ACLMessage.REQUEST) onCrossPrepare(m, c);
        else if (o instanceof PrepareOk ok && perf == ACLMessage.INFORM) onPrepareOk(m, ok);
        else if (o instanceof CrossCommit c && perf == ACLMessage.INFORM) onCrossCommit(m, c);
    }

    private void startSwap(ACLMessage m, SwapExec e) {
        Booking a = find(e.requesterId());
        if (a == null) { fail(m, "NO_BOOKING"); return; }
        if (locked(a.cargo.id())) { fail(m, "LOCKED"); return; }
        if (e.partnerWagonId().equals(me.id)) {                 // оба рейса в этом вагоне
            Booking b = find(e.partnerId());
            if (b == null || locked(b.cargo.id())) { fail(m, "NO_BOOKING_OR_LOCKED"); return; }
            SwapCtx s = new SwapCtx();
            s.a = a; s.b = b; s.ca = a.cargo; s.cb = b.cargo; s.aa = a.cargoAid; s.ab = b.cargoAid;
            s.cleanA = a.cleaning; s.cleanB = b.cleaning; s.request = m;
            a.cargo = s.cb; a.cargoAid = s.ab; b.cargo = s.ca; b.cargoAid = s.aa;      // пробный обмен грузов
            if (!(ready(a) && ready(b) && sequenceValid())) { rollback(s); fail(m, "SEQUENCE_INVALID"); return; }
            a.cleaning = cleaningBefore(a.trip.from(), a.cargo.cargoClass(), a.cargo.id());
            b.cleaning = cleaningBefore(b.trip.from(), b.cargo.cargoClass(), b.cargo.id());
            String conv = m.getConversationId() + "/loco";
            swaps.put(conv, s);
            sendMoves(conv, a.locoId, new ArrayList<>(List.of(
                    new LocoMove(me.id, a.trip, a.trip, gross(a.cargo)), new LocoMove(me.id, b.trip, b.trip, gross(b.cargo)))));
        } else if (!cfg.settings.allowCrossWagonSwap) {
            fail(m, "CROSS_WAGON_DISABLED");
        } else {                                                // фаза 1: спрашиваем вагон партнёра
            AID w = Df.find(this, Msg.SVC_WAGON, e.partnerWagonId());
            if (w == null) { fail(m, "NO_PARTNER_WAGON"); return; }
            CrossCtx x = new CrossCtx();
            x.key = m.getConversationId() + "/x"; x.a = a; x.request = m; x.partnerWagon = w;
            cross.put(x.key, x);
            lock(a.cargo.id());
            ACLMessage q = new ACLMessage(ACLMessage.REQUEST);
            q.setProtocol(Msg.P_SWAP_EXEC);
            q.setConversationId(x.key);
            q.addReceiver(w);
            Msg.put(q, new CrossPrepare(e.requesterId(), e.partnerId(), a.cargo, a.cargoAid));
            send(q);
        }
    }

    /** Вагон партнёра: может ли он взять груз просящего вместо своего. */
    private void onCrossPrepare(ACLMessage m, CrossPrepare c) {
        Booking b = find(c.partnerId());
        ACLMessage r = m.createReply();
        if (b == null || locked(b.cargo.id()) || !acceptable(b, c.cargo())) {
            r.setPerformative(ACLMessage.FAILURE); r.setContent("PARTNER_CANNOT_TAKE");
        } else {
            lock(b.cargo.id());                               // бронь партнёра не меняется до фиксации
            r.setPerformative(ACLMessage.INFORM);
            Msg.put(r, new PrepareOk(me.id, b.cargo, b.cargoAid, b.trip, b.tripMin, b.locoId, b.base, me.tareT));
        }
        send(r);
    }

    /** Наш вагон: можем ли взять груз партнёра; если да – атомарно меняем тягу у локомотива. */
    private void onPrepareOk(ACLMessage m, PrepareOk ok) {
        CrossCtx x = cross.get(m.getConversationId());
        if (x == null) return;
        Booking a = x.a;
        if (!a.locoId.equals(ok.locoId())) { endCross(x); fail(x.request, "DIFFERENT_LOCOS"); return; }   // TODO
        if (!acceptable(a, ok.cargo())) { endCross(x); fail(x.request, "SEQUENCE_INVALID"); return; }
        x.ok = ok;
        cross.put(x.key + "/loco", x);
        sendMoves(x.key + "/loco", a.locoId, new ArrayList<>(List.of(
                new LocoMove(me.id, a.trip, a.trip, gross(ok.cargo())),
                new LocoMove(ok.wagonId(), ok.trip(), ok.trip(), a.cargo.weightT() + ok.tareT()))));
    }

    private void onCrossLoco(ACLMessage m, CrossCtx x) {
        endCross(x);
        if (m.getPerformative() != ACLMessage.INFORM) { fail(x.request, "LOCO_REFUSED_SWAP"); return; }
        Booking a = x.a;
        Msg.CargoInfo mine = a.cargo; AID mineAid = a.cargoAid;
        a.cargo = x.ok.cargo(); a.cargoAid = x.ok.cargoAid();            // фаза 2: фиксируем у себя
        a.cleaning = cleaningBefore(a.trip.from(), a.cargo.cargoClass(), a.cargo.id());
        ACLMessage c = new ACLMessage(ACLMessage.INFORM);                // ... и у вагона партнёра
        c.setProtocol(Msg.P_SWAP_EXEC);
        c.setConversationId(x.key);
        c.addReceiver(x.partnerWagon);
        Msg.put(c, new CrossCommit(mine, mineAid, a.cargo.id()));
        send(c);
        notifySwap(a.cargoAid, x.key, new SwapDone(null, placementOf(a)));   // партнёр узнаёт новый рейс
        log("обмен между вагонами: " + mine.id() + " <-> " + a.cargo.id());
    }

    private void onCrossCommit(ACLMessage m, CrossCommit c) {
        Booking b = find(c.partnerId());
        locks.remove(c.partnerId());
        if (b == null) return;                                // вагон мог отказать между фазами
        b.cargo = c.cargo(); b.cargoAid = c.cargoAid();
        b.cleaning = cleaningBefore(b.trip.from(), b.cargo.cargoClass(), b.cargo.id());
        notifySwap(b.cargoAid, m.getConversationId(), new SwapDone(placementOf(b), null));   // просящий узнаёт новый рейс
    }

    private void onSwapLoco(ACLMessage m, SwapCtx s) {
        if (m.getPerformative() != ACLMessage.INFORM) { rollback(s); fail(s.request, "LOCO_REFUSED_SWAP"); return; }
        SwapDone d = new SwapDone(placementOf(s.b), placementOf(s.a));   // в слоте b теперь груз просящего
        ACLMessage ok = s.request.createReply();
        ok.setPerformative(ACLMessage.INFORM);
        Msg.put(ok, d);
        send(ok);
        notifySwap(s.a.cargoAid, s.request.getConversationId(), d);
        log("обмен рейсов " + s.ca.id() + " <-> " + s.cb.id());
    }

    private boolean sequenceValid() {
        List<Booking> l = new ArrayList<>(bookings);
        l.sort(Comparator.comparingInt(b -> b.trip.from()));
        if (!l.isEmpty() && l.get(0).trip.from() < cfg.settings.dayStartMin + emptyMin(me.home, l.get(0).cargo.from()) + cfg.settings.loadMin) return false;
        for (int i = 1; i < l.size(); i++) {
            Booking p = l.get(i - 1), n = l.get(i);
            if (n.trip.from() < p.trip.to() + gap(p.cargo, n.cargo)) return false;
        }
        return true;
    }

    private void rollback(SwapCtx s) {
        s.a.cargo = s.ca; s.a.cargoAid = s.aa; s.b.cargo = s.cb; s.b.cargoAid = s.ab;
        s.a.cleaning = s.cleanA; s.b.cleaning = s.cleanB;
    }

    private void fail(ACLMessage req, String why) {
        ACLMessage f = req.createReply(); f.setPerformative(ACLMessage.FAILURE); f.setContent(why); send(f);
    }

    // ===================== Незапланированная ситуация: отказ вагона =====================

    private void breakdown(int until) {
        if (broken) return;
        broken = true; repairUntil = until;
        holds.clear();
        for (SwapCtx s : new ArrayList<>(swaps.values())) { rollback(s); fail(s.request, "WAGON_BROKEN"); }
        swaps.clear();
        for (CrossCtx x : new LinkedHashSet<>(cross.values())) fail(x.request, "WAGON_BROKEN");
        cross.clear(); locks.clear();
        addBehaviour(new WakerBehaviour(this, cfg.settings.repairAfterMs) {     // ремонт завершён
            @Override protected void onWake() { broken = false; log("вагон отремонтирован, снова принимает грузы"); }
        });
        log("ОТКАЗ вагона, ремонт до " + Time.fmt(until));
        List<String> affected = new ArrayList<>();
        for (Booking b : bookings) {
            affected.add(b.cargo.id());
            ACLMessage c = new ACLMessage(ACLMessage.REQUEST);   // отмена брони у локомотива
            c.setProtocol(Msg.P_BOOK_LOCO);
            c.setConversationId("cancel#" + b.cargo.id());
            c.addReceiver(Df.find(this, Msg.SVC_LOCO, b.locoId));
            Msg.put(c, new LocoCancel(me.id, b.trip));
            send(c);
            ACLMessage n = new ACLMessage(ACLMessage.INFORM);    // уведомление груза
            n.setProtocol(Msg.P_BREAKDOWN);
            n.addReceiver(b.cargoAid);
            Msg.put(n, new Breakdown(me.id, until));
            send(n);
        }
        bookings.clear();
        AID k = Df.find(this, Msg.SVC_COORD, "coordinator");     // координатору – только информация
        if (k != null) {
            ACLMessage r = new ACLMessage(ACLMessage.INFORM);
            r.setProtocol(Msg.P_REPORT);
            r.addReceiver(k);
            Msg.put(r, new BreakdownReport(me.id, until, affected));
            send(r);
        }
    }
}
