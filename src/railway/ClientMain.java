package railway;

import jade.wrapper.ContainerController;
import railway.io.Config;

/**
 * Клиент. Аргументы: <IP сервера> <cargo|wagons> [префикс] [свойIP] [input.json]
 * cargo  – агенты грузов, wagons – агенты вагонов (+ консоль ввода отказа).
 */
public class ClientMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.out.println("Использование: ClientMain <IP сервера> <cargo|wagons> [префикс] [свойIP] [input.json]"); return; }
        String prefix = args.length > 2 ? args[2] : "pc2";
        String myIp = args.length > 3 && !args[3].isBlank() ? args[3] : null;
        Config cfg = Config.load(args.length > 4 ? args[4] : "data/input.json");
        ContainerController cc = Launcher.clientContainer(args[0], myIp);
        switch (args[1]) {
            case "cargo" -> Launcher.startCargoes(cc, cfg, prefix);
            case "wagons" -> Launcher.failureConsole(Launcher.startWagons(cc, cfg, prefix));
            default -> System.out.println("Неизвестный режим: " + args[1]);
        }
    }
}
