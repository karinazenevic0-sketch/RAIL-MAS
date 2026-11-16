package railway;

import jade.wrapper.ContainerController;
import railway.io.Config;

/** Сервер: главный контейнер + координатор, станции, локомотивы. Аргументы: [свойIP] [префикс] [input.json] */
public class ServerMain {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 && !args[0].isBlank() ? args[0] : null;
        String prefix = args.length > 1 ? args[1] : "pc1";
        Config cfg = Config.load(args.length > 2 ? args[2] : "data/input.json");
        ContainerController cc = Launcher.mainContainer(host);
        Launcher.startServerAgents(cc, cfg, prefix);
    }
}
