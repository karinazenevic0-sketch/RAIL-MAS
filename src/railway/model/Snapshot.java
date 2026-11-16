package railway.model;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/** Снимок плана, который сохраняет координатор (initial / improved / recovery). */
public record Snapshot(Phase phase, Map<String, Placement> plan, List<String> unplaced,
                       double cost, int lateMin, List<String> violations, String verdict) implements Serializable {}
