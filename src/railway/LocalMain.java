package railway;

import jade.wrapper.ContainerController;
import railway.io.Config;

/** Всё на одном компьютере: сервер + грузы + вагоны в одном контейнере. */
public class LocalMain {
    public static void main(String[] args) throws Exception {
        Config cfg = Config.load(args.length > 0 ? args[0] : "data/input.json");
        ContainerController cc = Launcher.mainContainer(null);
        Launcher.startServerAgents(cc, cfg, "pc1");
        Launcher.failureConsole(Launcher.startWagons(cc, cfg, "pc1"));
        Launcher.startCargoes(cc, cfg, "pc1");
    }
}
