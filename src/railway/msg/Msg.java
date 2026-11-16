package railway.msg;

import jade.lang.acl.ACLMessage;
import jade.lang.acl.UnreadableException;
import railway.model.Phase;
import railway.model.Placement;
import railway.model.Slot;

import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.util.List;

/** Константы протоколов и содержимое сообщений между агентами. */
public final class Msg {
    private Msg() {}

    // типы сервисов в DF
    public static final String SVC_COORD = "coordinator", SVC_STATION = "station", SVC_LOCO = "locomotive",
            SVC_WAGON = "wagon", SVC_CARGO = "cargo";

    // протоколы (ACLMessage.protocol)
    public static final String P_TRANSPORT = "transport";     // груз <-> вагон
    public static final String P_TRACTION = "traction";       // вагон <-> локомотив: запрос тяги
    public static final String P_BOOK_LOCO = "book-loco";     // вагон <-> локомотив: бронь / отмена / обмен
    public static final String P_WINDOW = "window";           // груз <-> станция
    public static final String P_SWAP_QUERY = "swap-query";   // груз <-> груз (этап 2)
    public static final String P_SWAP_EXEC = "swap-exec";     // груз -> вагон -> грузы (этап 2)
    public static final String P_BREAKDOWN = "breakdown";     // вагон -> груз
    public static final String P_REPORT = "report";           // агент -> координатор (только информирование)

    // ---------- груз <-> вагон ----------
    /** Груз сам о себе знает всё: тип, вес, срок. Координатор этого груза не рассылает. */
    public record CargoInfo(String id, String name, String cargoClass, double weightT,
                            String from, String to, int readyMin, int deadlineMin) implements Serializable {}

    public record TransportRequest(CargoInfo cargo, int notBefore, boolean allowAfterRepair) implements Serializable {}

    /** Предложение вагона. Стоимость считает вагон. */
    public record TransportOffer(String wagonId, String locoId, Slot trip, int arrive, int lateMin,
                                 double baseCost, double penalty, double emptyKm, Slot cleaning) implements Serializable {
        public double cost() { return baseCost + penalty; }
    }

    // ---------- вагон <-> локомотив ----------
    public record TractionRequest(String wagonId, double grossT, int notBefore, int durationMin) implements Serializable {}
    public record TractionOffer(String locoId, Slot slot) implements Serializable {}
    public record LocoBook(String wagonId, Slot slot, double grossT) implements Serializable {}
    public record LocoCancel(String wagonId, Slot slot) implements Serializable {}
    public record LocoMove(String wagonId, Slot from, Slot to, double grossT) implements Serializable {}
    /** Атомарный обмен броней: слот не остаётся пустым и не бронируется дважды. */
    public record LocoMoves(List<LocoMove> moves) implements Serializable {}

    // ---------- груз <-> станция ----------
    public record WindowRequest(String owner, String kind /* LOAD | UNLOAD */, Slot slot) implements Serializable {}
    /** Ответ станции при занятом окне: ближайшая свободная альтернатива того же вида. */
    public record WindowAlt(String kind, Slot slot) implements Serializable {}
    public record WindowSwap(String ownerA, String ownerB, String kind) implements Serializable {}

    // ---------- этап 2: груз спрашивает ВСЕ грузы ----------
    public record SwapQuery(CargoInfo requester, Placement requesterPlacement) implements Serializable {}
    /** Ответ груза, готового уступить свой рейс: его текущий рейс + запас по сроку. */
    public record SwapOffer(String cargoId, Placement placement, int slackMin, double penaltyDelta) implements Serializable {}
    public record SwapExec(String requesterId, String partnerId, String partnerWagonId) implements Serializable {}
    /** Обмен между разными вагонами: фаза 1 (вагон просящего -> вагон партнёра), фаза 2 – подтверждение. */
    public record CrossPrepare(String requesterId, String partnerId, CargoInfo cargo, jade.core.AID cargoAid) implements Serializable {}
    public record PrepareOk(String wagonId, CargoInfo cargo, jade.core.AID cargoAid, Slot trip, int tripMin,
                            String locoId, double base, double tareT) implements Serializable {}
    public record CrossCommit(CargoInfo cargo, jade.core.AID cargoAid, String partnerId) implements Serializable {}
    public record SwapDone(Placement requester, Placement partner) implements Serializable {}

    // ---------- отказ вагона ----------
    public record Breakdown(String wagonId, int repairUntil) implements Serializable {}
    public record FailCommand(String repairUntil) implements Serializable {}   // ввод отказа вручную (O2A)

    // ---------- отчёты координатору ----------
    public record Report(Phase phase, String cargoId, Placement placement, String note) implements Serializable {}
    public record BreakdownReport(String wagonId, int repairUntil, List<String> affectedCargo) implements Serializable {}

    // ---------- утилиты ----------
    public static void put(ACLMessage m, Serializable o) {
        try { m.setContentObject(o); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** Как get, но возвращает null, если в сообщении нет объекта нужного типа. */
    public static <T> T tryGet(ACLMessage m, Class<T> c) {
        try { Object o = m.getContentObject(); return c.isInstance(o) ? c.cast(o) : null; } catch (Exception e) { return null; }
    }

    public static <T> T get(ACLMessage m, Class<T> c) {
        try { return c.cast(m.getContentObject()); } catch (UnreadableException e) { throw new RuntimeException(e); }
    }
}
