package railway.model;

import java.io.Serializable;

/**
 * Итог размещения одного груза: какой вагон, какой локомотив, когда.
 * Числа стоимости считает ВАГОН (базовая стоимость = тариф рейса + порожний пробег).
 * trip – весь оборот вагона (отправление + возврат), arrive – прибытие груза на станцию назначения.
 */
public record Placement(String cargoId, String wagonId, String locoId, String from, String to,
                        Slot trip, int arrive, int lateMin,
                        double baseCost, double penalty, double grossT,
                        Slot cleaning, Slot loadWindow, Slot unloadWindow) implements Serializable {
    public double cost() { return baseCost + penalty; }
}
