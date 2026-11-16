package railway.agents;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import railway.io.Config;
import railway.model.Slot;
import railway.msg.Msg;
import railway.msg.Msg.*;

import java.util.*;

/**
 * Агент локомотива (сервер). Хранит брони по интервалам. Ограничение – суммарная тяга:
 * брутто всех составов, пересекающихся по времени, не больше тяги локомотива.
 * Предложение (CFP) интервал НЕ блокирует – конфликт решается при бронировании (кто первый).
 */
public class LocomotiveAgent extends Agent {
    private record LocoBooking(String wagonId, Slot slot, double gross) {}

    private Config.Locomotive me;
    private final List<LocoBooking> book = new ArrayList<>();

    @Override
    protected void setup() {
        me = (Config.Locomotive) getArguments()[1];
        Df.register(this, Msg.SVC_LOCO, me.id);
        addBehaviour(new CyclicBehaviour(this) {
            @Override public void action() {
                ACLMessage m = receive();
                if (m == null) { block(); return; }
                handleMessage(m);
            }
        });
    }

    private void handleMessage(ACLMessage m) {
        String p = m.getProtocol();
        if (p == null) return;
        if (p.equals(Msg.P_TRACTION) && m.getPerformative() == ACLMessage.CFP) {
            TractionRequest r = Msg.get(m, TractionRequest.class);
            ACLMessage rep = m.createReply();
            if (r.grossT() > me.tractionT) {
                rep.setPerformative(ACLMessage.REFUSE);
                rep.setContent("TRACTION_TOO_LOW");
            } else {
                rep.setPerformative(ACLMessage.PROPOSE);
                Msg.put(rep, new TractionOffer(me.id, earliest(r.notBefore(), r.durationMin(), r.grossT())));
            }
            send(rep);
        } else if (p.equals(Msg.P_BOOK_LOCO) && m.getPerformative() == ACLMessage.REQUEST) {
            Object o = Msg.get(m, Object.class);
            ACLMessage rep = m.createReply();
            if (o instanceof LocoBook b) {
                if (fits(b.slot(), b.grossT())) {
                    book.add(new LocoBooking(b.wagonId(), b.slot(), b.grossT()));
                    rep.setPerformative(ACLMessage.INFORM);
                } else { // отказ + альтернатива
                    rep.setPerformative(ACLMessage.FAILURE);
                    Msg.put(rep, new TractionOffer(me.id, earliest(b.slot().from(), b.slot().len(), b.grossT())));
                }
            } else if (o instanceof LocoCancel c) {
                book.removeIf(x -> x.wagonId().equals(c.wagonId()) && x.slot().equals(c.slot()));
                rep.setPerformative(ACLMessage.INFORM);
            } else if (o instanceof LocoMoves mv) {
                rep.setPerformative(applyMoves(mv) ? ACLMessage.INFORM : ACLMessage.FAILURE);
            } else return;
            send(rep);
        }
    }

    private double loadAt(Slot s) {
        double sum = 0;
        for (LocoBooking b : book) if (b.slot().overlaps(s)) sum += b.gross();
        return sum;
    }

    private boolean fits(Slot s, double gross) { return loadAt(s) + gross <= me.tractionT + 1e-9; }

    /** Ближайший интервал не раньше notBefore, где хватает тяги (кандидаты – концы существующих броней). */
    private Slot earliest(int notBefore, int dur, double gross) {
        TreeSet<Integer> cand = new TreeSet<>();
        cand.add(notBefore);
        for (LocoBooking b : book) if (b.slot().to() > notBefore) cand.add(b.slot().to());
        for (int s : cand) {
            Slot x = new Slot(s, s + dur);
            if (fits(x, gross)) return x;
        }
        return new Slot(cand.last(), cand.last() + dur);
    }

    /** Все перемещения применяются как одна операция; при неудаче – откат. */
    private boolean applyMoves(LocoMoves mv) {
        List<LocoBooking> backup = new ArrayList<>(book);
        for (LocoMove x : mv.moves())
            book.removeIf(b -> b.wagonId().equals(x.wagonId()) && b.slot().equals(x.from()));
        for (LocoMove x : mv.moves()) {
            if (!fits(x.to(), x.grossT())) { book.clear(); book.addAll(backup); return false; }
            book.add(new LocoBooking(x.wagonId(), x.to(), x.grossT()));
        }
        return true;
    }
}
