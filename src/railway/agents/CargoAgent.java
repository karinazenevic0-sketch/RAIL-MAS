package railway.agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import railway.io.Config;
import railway.model.Phase;
import railway.model.Placement;
import railway.model.Slot;
import railway.model.Time;
import railway.msg.Msg;
import railway.msg.Msg.*;

import java.util.*;

/**
 * Агент груза (клиент). САМ знает свой тип, вес, станции и дедлайн – координатор ему ничего не рассылает.
 * Этап 1: рассылает запрос всем вагонам, выбирает минимальную стоимость (её считает вагон), бронирует,
 *         затем бронирует окна станций и сообщает координатору.
 * Этап 2: если опаздывает – САМ опрашивает ВСЕ остальные грузы, кто может уступить рейс,
 *         выбирает самый выгодный и меняется с ним.
 * Отказ вагона: получив уведомление, сам (с задержкой, тем меньшей, чем меньше запас по сроку) ищет замену.
 */
public class CargoAgent extends Agent {
    private enum St { WAIT_SYSTEM, SEARCH, RETRY_WAIT, BOOKING, WINDOWS, PLACED, SWAP_QUERY, SWAP_WAIT, SWAP_EXEC, REPLAN_WAIT, FAILED }

    private Config cfg;
    private Config.Cargo spec;
    private CargoInfo info;
    private St st = St.WAIT_SYSTEM;
    private Phase phase = Phase.STAGE1;
    private Placement current;

    private int windowRetries, seq, retries, notBefore, repairUntil, windowAcks, swapRounds, expected, replies, swapExpected, swapReplies;
    private boolean afterRepair, blocked;
    private long nextCheck, deadlineTs, retryAt, replanAt, stage2At, swapLockUntil;
    private String convId, swapPartnerId, swapPartnerWagon;
    private AID swapLockAid;
    private final Map<AID, TransportOffer> offers = new LinkedHashMap<>();
    private final Map<AID, SwapOffer> swapOffers = new LinkedHashMap<>();

    @Override
    protected void setup() {
        cfg = (Config) getArguments()[0];
        spec = (Config.Cargo) getArguments()[1];
        info = new CargoInfo(spec.id, spec.name, spec.cargoClass, spec.weightT, spec.from, spec.to,
                Time.parse(spec.ready), Time.parse(spec.deadline));
        Df.register(this, Msg.SVC_CARGO, spec.id);
        addBehaviour(new CyclicBehaviour(this) {
            @Override public void action() {
                tick();
                ACLMessage m = receive();
                if (m == null) { block(150); return; }
                onMessage(m);
            }
        });
    }

    private void log(String s) { System.out.println("[" + getLocalName() + "] " + s); }

    // ===================== Таймеры состояний =====================

    private void tick() {
        long now = System.currentTimeMillis();
        switch (st) {
            case WAIT_SYSTEM -> {
                if (now >= nextCheck) {
                    nextCheck = now + 500;
                    if (systemReady()) startSearch(info.readyMin() + cfg.settings.prepMin, false, Phase.STAGE1);
                }
            }
            case SEARCH -> { if (now >= deadlineTs) decide(); }
            case RETRY_WAIT -> { if (now >= retryAt) startSearch(notBefore, afterRepair, phase); }
            case PLACED -> {
                if (stage2At > 0 && now >= stage2At && current != null && current.lateMin() > 0) { stage2At = 0; startSwapQuery(); }
            }
            case SWAP_QUERY -> { if (now >= deadlineTs) decideSwap(); }
            case REPLAN_WAIT -> { if (now >= replanAt) startSearch(info.readyMin() + cfg.settings.prepMin, false, Phase.RECOVERY); }
            default -> {}
        }
    }

    private boolean systemReady() {
        return Df.count(this, Msg.SVC_WAGON) >= cfg.wagons.size()
                && Df.count(this, Msg.SVC_LOCO) >= cfg.locomotives.size()
                && Df.count(this, Msg.SVC_STATION) >= cfg.stations.size()
                && Df.count(this, Msg.SVC_COORD) >= 1;
    }

    private void onMessage(ACLMessage m) {
        String p = m.getProtocol();
        if (p == null) return;
        switch (p) {
            case Msg.P_TRANSPORT -> onTransport(m);
            case Msg.P_WINDOW -> onWindow(m);
            case Msg.P_SWAP_QUERY -> onSwapQuery(m);
            case Msg.P_SWAP_EXEC -> onSwapExec(m);
            case Msg.P_BREAKDOWN -> onBreakdown(m);
            default -> {}
        }
    }

    // ===================== Этап 1: поиск вагона =====================

    private void startSearch(int nb, boolean afterRepair, Phase ph) {
        this.phase = ph; this.notBefore = nb; this.afterRepair = afterRepair;
        convId = spec.id + "#" + (++seq);
        List<AID> wagons = Df.all(this, Msg.SVC_WAGON);
        offers.clear(); replies = 0; blocked = false; expected = wagons.size();
        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
        cfp.setProtocol(Msg.P_TRANSPORT);
        cfp.setConversationId(convId);
        wagons.forEach(cfp::addReceiver);
        Msg.put(cfp, new TransportRequest(info, nb, afterRepair));
        send(cfp);
        deadlineTs = System.currentTimeMillis() + cfg.settings.negotiationTimeoutMs;
        st = St.SEARCH;
        log("запрос всем вагонам, не раньше " + Time.fmt(nb) + (afterRepair ? " (допускаю ожидание ремонта)" : ""));
    }

    private void onTransport(ACLMessage m) {
        if (!Objects.equals(m.getConversationId(), convId)) return;     // устаревший диалог
        switch (m.getPerformative()) {
            case ACLMessage.PROPOSE -> { if (st == St.SEARCH) { offers.put(m.getSender(), Msg.get(m, TransportOffer.class)); replies++; } }
            case ACLMessage.REFUSE -> { if (st == St.SEARCH) { replies++; if ("BLOCKED".equals(m.getContent()) || "CONFLICT".equals(m.getContent())) blocked = true; } }
            case ACLMessage.INFORM -> {
                if (st == St.BOOKING) { current = Msg.get(m, Placement.class); st = St.WINDOWS; bookWindows(); }
            }
            case ACLMessage.FAILURE -> {
                if (st == St.BOOKING) { log("бронь не удалась: " + m.getContent()); retry(); }
            }
            default -> {}
        }
        if (st == St.SEARCH && replies >= expected) decide();
    }

    private void decide() {
        if (st != St.SEARCH) return;
        if (offers.isEmpty()) {
            if (blocked && retries++ < cfg.settings.maxRetries) { retryAt = System.currentTimeMillis() + 1000; st = St.RETRY_WAIT; return; }
            if (phase == Phase.RECOVERY && !afterRepair) {     // замены нет – допустимый компромисс: ждать ремонта
                report("нет замены до срока, ожидаю ремонт вагона (компромисс)");
                startSearch(notBefore, true, phase);
                return;
            }
            st = St.FAILED; current = null;
            report("UNPLACED: нет допустимых предложений");
            log("не размещён");
            return;
        }
        // критерий: минимум стоимости (штраф за опоздание уже включён вагоном)
        AID best = Collections.min(offers.entrySet(), Comparator.<Map.Entry<AID, TransportOffer>>comparingDouble(e -> e.getValue().cost())).getKey();
        for (AID w : offers.keySet()) {
            ACLMessage a = new ACLMessage(w.equals(best) ? ACLMessage.ACCEPT_PROPOSAL : ACLMessage.REJECT_PROPOSAL);
            a.setProtocol(Msg.P_TRANSPORT);
            a.setConversationId(convId);
            a.addReceiver(w);
            send(a);
        }
        st = St.BOOKING;
        log("выбран " + best.getLocalName() + ", стоимость " + offers.get(best).cost());
    }

    private void retry() {
        if (retries++ < cfg.settings.maxRetries) startSearch(notBefore, afterRepair, phase);
        else { st = St.FAILED; report("UNPLACED: исчерпаны попытки"); }
    }

    // ===================== Окна станций =====================

    private void bookWindows() {
        windowAcks = 0; windowRetries = 0;
        sendStation(current.from(), new WindowRequest(spec.id, "LOAD", current.loadWindow()));
        sendStation(current.to(), new WindowRequest(spec.id, "UNLOAD", current.unloadWindow()));
    }

    private void sendStation(String stationId, java.io.Serializable payload) {
        AID s = Df.find(this, Msg.SVC_STATION, stationId);
        ACLMessage r = new ACLMessage(ACLMessage.REQUEST);
        r.setProtocol(Msg.P_WINDOW);
        r.setConversationId(convId);
        r.addReceiver(s);
        Msg.put(r, payload);
        send(r);
    }

    private void onWindow(ACLMessage m) {
        if (m.getPerformative() == ACLMessage.FAILURE) {
            WindowAlt alt = Msg.tryGet(m, WindowAlt.class);
            if (alt != null && st == St.WINDOWS && windowRetries++ < 3 && (alt.kind().equals("UNLOAD") || alt.slot().from() >= info.readyMin())) {
                current = withWindow(alt);                                   // берём альтернативное окно
                sendStation(alt.kind().equals("LOAD") ? current.from() : current.to(), new WindowRequest(spec.id, alt.kind(), alt.slot()));
                log("окно " + alt.kind() + " занято, пробую " + alt.slot());
                return;
            }
            log("окно станции не подтверждено");
            report("окно станции не подтверждено");
        } else if (m.getPerformative() != ACLMessage.INFORM) return;
        if (++windowAcks < 2) return;
        if (st == St.WINDOWS) placed();
        else if (st == St.SWAP_EXEC) swapFinished();
    }

    private Placement withWindow(WindowAlt a) {
        Placement c = current;
        return new Placement(c.cargoId(), c.wagonId(), c.locoId(), c.from(), c.to(), c.trip(), c.arrive(), c.lateMin(), c.baseCost(),
                c.penalty(), c.grossT(), c.cleaning(), a.kind().equals("LOAD") ? a.slot() : c.loadWindow(),
                a.kind().equals("UNLOAD") ? a.slot() : c.unloadWindow());
    }

    private void placed() {
        st = St.PLACED;
        retries = 0;
        report(null);
        log("размещён: " + current.wagonId() + " " + current.trip() + ", прибытие " + Time.fmt(current.arrive())
                + ", опоздание " + current.lateMin() + " мин, стоимость " + current.cost());
        if (phase == Phase.STAGE1 && current.lateMin() > 0) stage2At = System.currentTimeMillis() + cfg.settings.stage2DelayMs;
    }

    // ===================== Этап 2: груз сам спрашивает все грузы =====================

    private void startSwapQuery() {
        List<AID> others = Df.all(this, Msg.SVC_CARGO);
        others.remove(getAID());
        if (others.isEmpty()) return;
        convId = spec.id + "#swap" + (++seq);
        swapOffers.clear(); swapReplies = 0; swapExpected = others.size();
        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
        cfp.setProtocol(Msg.P_SWAP_QUERY);
        cfp.setConversationId(convId);
        others.forEach(cfp::addReceiver);
        Msg.put(cfp, new SwapQuery(info, current));
        send(cfp);
        deadlineTs = System.currentTimeMillis() + cfg.settings.negotiationTimeoutMs;
        st = St.SWAP_QUERY;
        log("опаздываю на " + current.lateMin() + " мин – спрашиваю все грузы, кто может уступить рейс");
    }

    private void onSwapQuery(ACLMessage m) {
        long now = System.currentTimeMillis();
        switch (m.getPerformative()) {
            // ---- роль «уступающий» ----
            case ACLMessage.CFP -> {
                SwapQuery q = Msg.get(m, SwapQuery.class);
                ACLMessage r = m.createReply();
                Placement rp = q.requesterPlacement();
                boolean locked = swapLockAid != null && now < swapLockUntil && !swapLockAid.equals(m.getSender());
                if (st != St.PLACED || current == null || locked) { swapRefuse(r, "busy"); return; }
                if (!rp.wagonId().equals(current.wagonId()) && !cfg.settings.allowCrossWagonSwap) { swapRefuse(r, "cross-wagon off"); return; }
                if (!q.requester().from().equals(info.from()) || !q.requester().to().equals(info.to())) { swapRefuse(r, "other route"); return; }
                int newArrive = rp.trip().from() + (current.arrive() - current.trip().from());   // если возьму слот просящего
                int newLate = Math.max(0, newArrive - info.deadlineMin());
                if (newLate > current.lateMin()) { swapRefuse(r, "no slack"); return; }          // условия не ухудшаются
                double dPen = cfg.costs.latePerHour * (newLate - current.lateMin()) / 60.0;
                swapLockAid = m.getSender(); swapLockUntil = now + 2 * cfg.settings.negotiationTimeoutMs;
                r.setPerformative(ACLMessage.PROPOSE);
                Msg.put(r, new SwapOffer(spec.id, current, info.deadlineMin() - newArrive, dPen));
                send(r);
            }
            case ACLMessage.ACCEPT_PROPOSAL -> {
                ACLMessage r = m.createReply();
                boolean ok = swapLockAid != null && swapLockAid.equals(m.getSender()) && now < swapLockUntil && st == St.PLACED;
                r.setPerformative(ok ? ACLMessage.INFORM : ACLMessage.FAILURE);
                send(r);
            }
            case ACLMessage.REJECT_PROPOSAL -> { if (m.getSender().equals(swapLockAid)) swapLockAid = null; }
            // ---- роль «просящий» ----
            case ACLMessage.PROPOSE -> {
                if (st == St.SWAP_QUERY && Objects.equals(m.getConversationId(), convId)) {
                    swapOffers.put(m.getSender(), Msg.get(m, SwapOffer.class)); swapReplies++;
                }
            }
            case ACLMessage.REFUSE -> { if (st == St.SWAP_QUERY && Objects.equals(m.getConversationId(), convId)) swapReplies++; }
            case ACLMessage.INFORM -> {       // партнёр согласился -> просим свой вагон выполнить обмен
                if (st != St.SWAP_WAIT) return;
                ACLMessage x = new ACLMessage(ACLMessage.REQUEST);
                x.setProtocol(Msg.P_SWAP_EXEC);
                x.setConversationId(convId);
                x.addReceiver(Df.find(this, Msg.SVC_WAGON, current.wagonId()));
                Msg.put(x, new SwapExec(spec.id, swapPartnerId, swapPartnerWagon));
                send(x);
                st = St.SWAP_EXEC;
            }
            case ACLMessage.FAILURE -> { if (st == St.SWAP_WAIT) { st = St.PLACED; log("партнёр отказался от обмена"); } }
            default -> {}
        }
        if (st == St.SWAP_QUERY && swapReplies >= swapExpected) decideSwap();
    }

    private void swapRefuse(ACLMessage r, String why) { r.setPerformative(ACLMessage.REFUSE); r.setContent(why); send(r); }

    private void decideSwap() {
        if (st != St.SWAP_QUERY) return;
        AID best = null; double bestGain = Math.max(1e-6, cfg.settings.epsilon * current.cost());   // улучшение должно быть > ε
        for (Map.Entry<AID, SwapOffer> e : swapOffers.entrySet()) {
            Placement p = e.getValue().placement();
            int newLate = Math.max(0, p.arrive() - info.deadlineMin());
            double newCost = p.baseCost() + cfg.costs.latePerHour * newLate / 60.0;       // TODO: уточнять стоимость у вагона
            double gain = current.cost() - newCost - e.getValue().penaltyDelta();           // выгода с учётом ущерба партнёра
            if (gain > bestGain) { bestGain = gain; best = e.getKey(); }
        }
        if (best == null) { st = St.PLACED; log("выгодных обменов нет"); return; }
        for (AID a : swapOffers.keySet()) {
            ACLMessage r = new ACLMessage(a.equals(best) ? ACLMessage.ACCEPT_PROPOSAL : ACLMessage.REJECT_PROPOSAL);
            r.setProtocol(Msg.P_SWAP_QUERY);
            r.setConversationId(convId);
            r.addReceiver(a);
            send(r);
        }
        swapPartnerId = swapOffers.get(best).cargoId();
        swapPartnerWagon = swapOffers.get(best).placement().wagonId();
        st = St.SWAP_WAIT;
        log("выбран обмен с " + swapPartnerId + ", выгода " + bestGain);
    }

    private void onSwapExec(ACLMessage m) {
        if (m.getPerformative() == ACLMessage.FAILURE) {
            log("обмен не выполнен: " + m.getContent());
            if (st == St.SWAP_EXEC) st = St.PLACED;
            swapLockAid = null;
            return;
        }
        if (m.getPerformative() != ACLMessage.INFORM) return;
        SwapDone d = Msg.get(m, SwapDone.class);
        boolean iAmRequester = d.requester() != null && d.requester().cargoId().equals(spec.id);
        current = iAmRequester ? d.requester() : d.partner();
        phase = Phase.STAGE2;
        swapLockAid = null;
        if (iAmRequester) {          // просящий меняет окна станций с партнёром одной операцией
            windowAcks = 0;
            sendStation(current.from(), new WindowSwap(spec.id, swapPartnerId, "LOAD"));
            sendStation(current.to(), new WindowSwap(spec.id, swapPartnerId, "UNLOAD"));
            st = St.SWAP_EXEC;
        } else {
            report(null);
            log("мой рейс уступлен: " + current.trip() + ", опоздание " + current.lateMin());
        }
    }

    private void swapFinished() {
        st = St.PLACED;
        report(null);
        log("обмен завершён: " + current.trip() + ", опоздание " + current.lateMin() + ", стоимость " + current.cost());
        if (current.lateMin() > 0 && ++swapRounds < cfg.settings.maxSwapRounds)
            stage2At = System.currentTimeMillis() + cfg.settings.stage2DelayMs;
    }

    // ===================== Отказ вагона =====================

    private void onBreakdown(ACLMessage m) {
        Breakdown b = Msg.get(m, Breakdown.class);
        if (current == null || !current.wagonId().equals(b.wagonId())) return;   // TODO: отказ во время обмена/бронирования
        swapLockAid = null; stage2At = 0;                    // прерываем обмен/ребалансировку, если шли
        int slack = info.deadlineMin() - current.arrive();
        repairUntil = b.repairUntil();
        current = null;
        // срочные (малый запас по сроку) ищут замену первыми
        replanAt = System.currentTimeMillis() + Math.max(0, slack) / 60 * cfg.settings.replanMsPerSlackHour;
        st = St.REPLAN_WAIT;
        retries = 0;
        log("мой вагон " + b.wagonId() + " отказал, ищу замену через " + (replanAt - System.currentTimeMillis()) + " мс");
    }

    // ===================== Информирование координатора =====================

    private void report(String note) {
        AID k = Df.find(this, Msg.SVC_COORD, "coordinator");
        if (k == null) return;
        ACLMessage r = new ACLMessage(ACLMessage.INFORM);
        r.setProtocol(Msg.P_REPORT);
        r.addReceiver(k);
        Msg.put(r, new Report(phase, spec.id, current, note));
        send(r);
    }
}
