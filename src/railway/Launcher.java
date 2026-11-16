package railway;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentController;
import jade.wrapper.ContainerController;
import jade.wrapper.StaleProxyException;
import railway.agents.*;
import railway.io.Config;
import railway.msg.Msg;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;

/** Общие функции запуска контейнеров и агентов (агенты создаются в коде программы). */
public final class Launcher {
    private Launcher() {}

    /** Сервер: главный контейнер (порт 1099). host == null – адрес определит JADE. */
    public static ContainerController mainContainer(String host) {
        Runtime rt = Runtime.instance();
        rt.setCloseVM(true);
        Profile p = new ProfileImpl();
        if (host != null) { p.setParameter(Profile.MAIN_HOST, host); p.setParameter(Profile.LOCAL_HOST, host); }
        p.setParameter(Profile.GUI, "true");
        return rt.createMainContainer(p);
    }

    /** Клиент: подключается к главному контейнеру на другой машине. */
    public static ContainerController clientContainer(String serverHost, String myIp) {
        Runtime rt = Runtime.instance();
        rt.setCloseVM(true);
        Profile p = new ProfileImpl();
        p.setParameter(Profile.MAIN_HOST, serverHost);
        p.setParameter(Profile.MAIN_PORT, "1099");
        if (myIp != null) p.setParameter(Profile.LOCAL_HOST, myIp);
        return rt.createAgentContainer(p);
    }

    /** Сервер: координатор + станции + локомотивы. Имена агентов с префиксом компьютера. */
    public static void startServerAgents(ContainerController cc, Config cfg, String prefix) throws StaleProxyException {
        cc.createNewAgent(prefix + "_coordinator", CoordinatorAgent.class.getName(), new Object[]{cfg}).start();
        for (Config.Station s : cfg.stations)
            cc.createNewAgent(prefix + "_" + s.id + "_station", StationAgent.class.getName(), new Object[]{cfg, s}).start();
        for (Config.Locomotive l : cfg.locomotives)
            cc.createNewAgent(prefix + "_" + l.id, LocomotiveAgent.class.getName(), new Object[]{cfg, l}).start();
    }

    /** Клиент: вагоны. Возвращает контроллеры (для ручного ввода отказа). */
    public static Map<String, AgentController> startWagons(ContainerController cc, Config cfg, String prefix) throws StaleProxyException {
        Map<String, AgentController> res = new LinkedHashMap<>();
        for (Config.Wagon w : cfg.wagons)
            res.put(w.id, startAgent(cc, prefix + "_" + w.id, WagonAgent.class, cfg, w));
        return res;
    }

    /** Клиент: грузы. */
    public static void startCargoes(ContainerController cc, Config cfg, String prefix) throws StaleProxyException {
        for (Config.Cargo c : cfg.cargoes) startAgent(cc, prefix + "_" + c.id, CargoAgent.class, cfg, c);
    }

    private static AgentController startAgent(ContainerController cc, String name, Class<?> cls, Object... args) throws StaleProxyException {
        AgentController ac = cc.createNewAgent(name, cls.getName(), args);
        ac.start();
        return ac;
    }

    /** Консоль ввода незапланированных ситуаций: «fail <id вагона> <ЧЧ:ММ окончания ремонта>». */
    public static void failureConsole(Map<String, AgentController> wagons) {
        Thread t = new Thread(() -> {
            System.out.println("Консоль отказов: fail <id вагона> <ЧЧ:ММ>, например: fail wagon_h1 20:00");
            Scanner sc = new Scanner(System.in);
            while (sc.hasNextLine()) {
                String[] p = sc.nextLine().trim().split("\\s+");
                if (p.length >= 3 && p[0].equals("fail") && wagons.containsKey(p[1])) {
                    try { wagons.get(p[1]).putO2AObject(new Msg.FailCommand(p[2]), AgentController.ASYNC); }
                    catch (StaleProxyException e) { e.printStackTrace(); }
                }
            }
        }, "failure-console");
        t.setDaemon(true);
        t.start();
    }
}
