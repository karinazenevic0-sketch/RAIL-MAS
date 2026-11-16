package railway.agents;

import jade.core.AID;
import jade.core.Agent;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

import java.util.ArrayList;
import java.util.List;

/** Обёртка над DF: регистрация и поиск агентов по типу (и имени) сервиса. */
public final class Df {
    private Df() {}

    public static void register(Agent a, String type, String name) {
        DFAgentDescription d = new DFAgentDescription();
        d.setName(a.getAID());
        ServiceDescription s = new ServiceDescription();
        s.setType(type);
        s.setName(name);
        d.addServices(s);
        try { DFService.register(a, d); } catch (FIPAException e) { throw new IllegalStateException(e); }
    }

    public static List<AID> all(Agent a, String type) { return search(a, type, null); }
    public static int count(Agent a, String type) { return search(a, type, null).size(); }

    public static AID find(Agent a, String type, String name) {
        List<AID> l = search(a, type, name);
        return l.isEmpty() ? null : l.get(0);
    }

    private static List<AID> search(Agent a, String type, String name) {
        DFAgentDescription t = new DFAgentDescription();
        ServiceDescription s = new ServiceDescription();
        s.setType(type);
        if (name != null) s.setName(name);
        t.addServices(s);
        List<AID> out = new ArrayList<>();
        try {
            for (DFAgentDescription d : DFService.search(a, t)) out.add(d.getName());
        } catch (FIPAException ignored) { }
        return out;
    }
}
