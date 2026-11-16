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
    }
    private static class SwapCtx { Booking a, b; Slot sa, sb; ACLMessage request; Slot cleanA, cleanB; }

    private Config cfg;
    private Config.Wagon me;
    private boolean broken;
    private int repairUntil;
    private final List<Booking> bookings = new ArrayList<>();
    private final Map<String, Hold> holds = new HashMap<>();
    private final Map<String, Ctx> ctx = new HashMap<>();
    private final Map<String, SwapCtx> swaps = new HashMap<>();

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
                handle(m);
            }
        });
    }

    private void log(String s) { System.out.println("[" + getLocalName() + "] " + s); }

    private void handle(ACLMessage m) {
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
        Config.Locomotive loco = cfg.locoAt(c.from());   // TODO: опрашивать все подходящие локомотивы
        if (link == null || loco == null) { refuse(m, "NO_ROUTE"); return; }

        int nb = broken ? Math.max(req.notBefore(), repairUntil) : req.notBefore();
        int start = findStart(c, nb, link.turnaroundMin);
        Slot trip = new Slot(start, start + link.turnaroundMin);
        if (overlapsHold(trip)) { refuse(m, "BLOCKED"); return; }   // интервал временно занят другим грузом

        Ctx x = new Ctx();
        x.cfp = m; x.cargo = c; x.link = link; x.cargoAid = m.getSender();
        x.gross = c.weightT() + me.tareT;
        x.emptyKm = bookings.isEmpty() ? cfg.km(me.home, c.from()) : 0; // TODO: учёт положения после рейсов и времени подачи
        ctx.put(m.getConversationId(), x);

        ACLMessage q = new ACLMessage(ACLMessage.CFP);
        q.setProtocol(Msg.P_TRACTION);
        q.setConversationId(m.getConversationId());
        q.addReceiver(Df.find(this, Msg.SVC_LOCO, loco.id));
        Msg.put(q, new TractionRequest(me.id, x.gross, start, link.turnaroundMin));
        send(q);
    }

    private void onTraction(ACLMessage m) {
        Ctx x = ctx.get(m.getConversationId());
        if (x == null) return;
        if (m.getPerformative() == ACLMessage.REFUSE) { ctx.remove(m.getConversationId()); refuse(x.cfp, "NO_TRACTION"); return; }
        if (m.getPerformative() != ACLMessage.PROPOSE) return;

        TractionOffer o = Msg.get(m, TractionOffer.class);
        Slot trip = o.slot();
        String cls = x.cargo.cargoClass();
        if (!fitsBookings(trip, cls, null) || overlapsHold(trip)) {      // локомотив сдвинул слот – перепроверка
            ctx.remove(m.getConversationId());
            refuse(x.cfp, "CONFLICT");
            return;
        }
        Hold h = new Hold();                                              // блок на время переговоров
        h.slot = trip; h.cls = cls; h.expires = System.currentTimeMillis() + cfg.settings.holdTtlMs;
        holds.put(m.getConversationId(), h);

        int arrive = trip.from() + x.link.tripMin;
        int late = Math.max(0, arrive - x.cargo.deadlineMin());
        double base = x.link.tripCost + cfg.costs.emptyKmRate * x.emptyKm;
        double pen = cfg.costs.latePerHour * late / 60.0;
        x.locoAid = m.getSender();
        x.offer = new TransportOffer(me.id, o.locoId(), trip, arrive, late, base, pen, x.emptyKm, cleaningBefore(trip.from(), cls, null));

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

    private int gap(String prevCls, String nextCls) {
        return cfg.settings.loadMin + (prevCls.equals(nextCls) ? 0 : cfg.settings.cleaningMin);
    }

    private int findStart(Msg.CargoInfo c, int nb, int dur) {
        int base = Math.max(nb, c.readyMin() + cfg.settings.prepMin);
        List<Integer> cand = new ArrayList<>(List.of(base));
        for (Booking b : bookings) cand.add(Math.max(base, b.trip.to() + gap(b.cargo.cargoClass(), c.cargoClass())));
        Collections.sort(cand);
        for (int s : cand) if (fitsBookings(new Slot(s, s + dur), c.cargoClass(), null)) return s;
        return cand.get(cand.size() - 1);
    }

    /** Нет пересечений с бронями; между рейсами хватает времени на погрузку и очистку при смене класса груза. */
    private boolean fitsBookings(Slot t, String cls, String ignoreCargo) {
        for (Booking b : bookings) {
            if (b.cargo.id().equals(ignoreCargo)) continue;
            if (t.from() >= b.trip.to()) { if (t.from() < b.trip.to() + gap(b.cargo.cargoClass(), cls)) return false; }
            else if (b.trip.from() >= t.to()) { if (b.trip.from() < t.to() + gap(cls, b.cargo.cargoClass())) return false; }
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

    // ===================== Этап 2: обмен рейсами внутри вагона =====================

    private void onSwapExec(ACLMessage m) {
        if (m.getPerformative() != ACLMessage.REQUEST) return;
        SwapExec e = Msg.get(m, SwapExec.class);
        Booking a = find(e.requesterId()), b = find(e.partnerId());
        if (a == null || b == null) {     // TODO: обмен между разными вагонами (двухфазное подтверждение двух вагонов)
            ACLMessage f = m.createReply(); f.setPerformative(ACLMessage.FAILURE); f.setContent("NOT_SAME_WAGON"); send(f); return;
        }
        SwapCtx s = new SwapCtx();
        s.a = a; s.b = b; s.sa = a.trip; s.sb = b.trip; s.cleanA = a.cleaning; s.cleanB = b.cleaning; s.request = m;
        a.trip = s.sb; b.trip = s.sa;                       // пробное изменение
        if (!sequenceValid()) { rollback(s); fail(m, "SEQUENCE_INVALID"); return; }   // очистка/погрузка между рейсами
        a.cleaning = cleaningBefore(a.trip.from(), a.cargo.cargoClass(), a.cargo.id());
        b.cleaning = cleaningBefore(b.trip.from(), b.cargo.cargoClass(), b.cargo.id());

        List<LocoMove> mv = new ArrayList<>();
        mv.add(new LocoMove(me.id, s.sa, s.sb, a.cargo.weightT() + me.tareT));
        mv.add(new LocoMove(me.id, s.sb, s.sa, b.cargo.weightT() + me.tareT));
        String conv = m.getConversationId() + "/loco";
        swaps.put(conv, s);
        ACLMessage q = new ACLMessage(ACLMessage.REQUEST);
        q.setProtocol(Msg.P_BOOK_LOCO);
        q.setConversationId(conv);
        q.addReceiver(Df.find(this, Msg.SVC_LOCO, a.locoId));
        Msg.put(q, new LocoMoves(mv));
        send(q);
    }

    private void onSwapLoco(ACLMessage m, SwapCtx s) {
        if (m.getPerformative() != ACLMessage.INFORM) { rollback(s); fail(s.request, "LOCO_REFUSED_SWAP"); return; }
        Placement pa = placementOf(s.a), pb = placementOf(s.b);
        ACLMessage ok = s.request.createReply();                 // заказчику обмена
        ok.setPerformative(ACLMessage.INFORM);
        Msg.put(ok, new SwapDone(pa, pb));
        send(ok);
        ACLMessage note = new ACLMessage(ACLMessage.INFORM);    // уведомление партнёру
        note.setProtocol(Msg.P_SWAP_EXEC);
        note.setConversationId(s.request.getConversationId());
        note.addReceiver(s.b.cargoAid);
        Msg.put(note, new SwapDone(pa, pb));
        send(note);
        log("обмен рейсов " + s.a.cargo.id() + " <-> " + s.b.cargo.id());
    }

    private boolean sequenceValid() {
        List<Booking> l = new ArrayList<>(bookings);
        l.sort(Comparator.comparingInt(b -> b.trip.from()));
        for (int i = 1; i < l.size(); i++) {
            Booking p = l.get(i - 1), n = l.get(i);
            if (n.trip.from() < p.trip.to() + gap(p.cargo.cargoClass(), n.cargo.cargoClass())) return false;
        }
        return true;
    }

    private void rollback(SwapCtx s) { s.a.trip = s.sa; s.b.trip = s.sb; s.a.cleaning = s.cleanA; s.b.cleaning = s.cleanB; }

    private void fail(ACLMessage req, String why) {
        ACLMessage f = req.createReply(); f.setPerformative(ACLMessage.FAILURE); f.setContent(why); send(f);
    }

    // ===================== Незапланированная ситуация: отказ вагона =====================

    private void breakdown(int until) {
        if (broken) return;
        broken = true; repairUntil = until;
        holds.clear();
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
