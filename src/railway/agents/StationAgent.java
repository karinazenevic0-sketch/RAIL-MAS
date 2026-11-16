package railway.agents;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import railway.io.Config;
import railway.model.Slot;
import railway.msg.Msg;
import railway.msg.Msg.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Агент станции (сервер). Окна погрузки / приёма с ограничением по числу путей (capacity).
 * Запрос от того же владельца и того же вида заменяет прежнее окно (перенос окна).
 */
public class StationAgent extends Agent {
    private record Win(String owner, String kind, Slot slot) {}

    private Config.Station me;
    private final List<Win> wins = new ArrayList<>();

    @Override
    protected void setup() {
        me = (Config.Station) getArguments()[1];
        Df.register(this, Msg.SVC_STATION, me.id);
        addBehaviour(new CyclicBehaviour(this) {
            @Override public void action() {
                ACLMessage m = receive();
                if (m == null) { block(); return; }
                if (Msg.P_WINDOW.equals(m.getProtocol()) && m.getPerformative() == ACLMessage.REQUEST) handleMessage(m);
            }
        });
    }

    private void handleMessage(ACLMessage m) {
        Object o = Msg.get(m, Object.class);
        ACLMessage rep = m.createReply();
        if (o instanceof WindowRequest r) {
            Win old = remove(r.owner(), r.kind());
            if (free(r.slot())) {
                wins.add(new Win(r.owner(), r.kind(), r.slot()));
                rep.setPerformative(ACLMessage.INFORM);
            } else {
                if (old != null) wins.add(old);
                rep.setPerformative(ACLMessage.FAILURE);
                Msg.put(rep, new WindowAlt(r.kind(), alternative(r.kind(), r.slot()))); // альтернатива
            }
        } else if (o instanceof WindowSwap s) {   // обмен окон двух грузов одной операцией
            Win a = get(s.ownerA(), s.kind()), b = get(s.ownerB(), s.kind());
            if (a == null || b == null) rep.setPerformative(ACLMessage.FAILURE);
            else {
                wins.remove(a); wins.remove(b);
                wins.add(new Win(a.owner(), a.kind(), b.slot()));
                wins.add(new Win(b.owner(), b.kind(), a.slot()));
                rep.setPerformative(ACLMessage.INFORM);
            }
        } else return;
        send(rep);
    }

    private Win get(String owner, String kind) {
        return wins.stream().filter(w -> w.owner().equals(owner) && w.kind().equals(kind)).findFirst().orElse(null);
    }

    private Win remove(String owner, String kind) {
        Win w = get(owner, kind);
        if (w != null) wins.remove(w);
        return w;
    }

    private boolean free(Slot s) { return wins.stream().filter(w -> w.slot().overlaps(s)).count() < me.capacity; }

    /** Погрузка должна закончиться до отправления – ищем РАНЬШЕ; приём можно сдвинуть ПОЗЖЕ. */
    private Slot alternative(String kind, Slot s) {
        int dir = kind.equals("LOAD") ? -1 : 1;
        for (int i = 1; i <= 12; i++) {
            Slot x = new Slot(s.from() + dir * 60 * i, s.to() + dir * 60 * i);
            if (free(x)) return x;
        }
        return s;
    }

    private Slot nextFree(Slot s) {
        for (int i = 1; i <= 24; i++) {
            Slot x = new Slot(s.from() + 60 * i, s.to() + 60 * i);
            if (free(x)) return x;
        }
        return s;
    }
}
