package idle_realms.core;

import idle_realms.core.EconomyException.Reason;
import idle_realms.core.EconomyState.BuildingView;
import idle_realms.core.EconomyState.PendingCommand;
import idle_realms.core.EconomyState.Rejection;
import idle_realms.core.EconomyState.RequestRecord;
import idle_realms.core.EconomyState.RequestStatus;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Textform eines {@link EconomySnapshot} für die Speicherung (Issue #7). Eine Zeile je Eintrag, Felder durch Leerzeichen getrennt;
 * die Kopfzeile nennt die Schemaversion. Die Form ist streng: unbekannte Versionen, Schlüssel und Feldanzahlen werden abgewiesen.
 * Die Ausgabe ist deterministisch, damit gleiche Zustände gleichen Text ergeben.
 */
public final class EconomySnapshotText {
    static final String VERSION_PREFIX = "idle-realms-economy ";
    static final String HEADER = VERSION_PREFIX + "1";
    /** Einzeilige Schlüssel mit ihrer Feldanzahl; Kopfzeile und Prüfung nutzen dieselbe Definition. */
    private static final Map<String, Integer> SINGLE_FIELDS = Map.of("realm", 2, "epoch", 2, "anchor", 2, "revision", 2, "next", 3);
    private static final String NONE = "-";

    private EconomySnapshotText() {}

    public static String encode(EconomySnapshot s) {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        line(out, "realm", s.realmId());
        line(out, "epoch", s.epoch());
        line(out, "anchor", s.anchor());
        line(out, "revision", s.revision());
        line(out, "next", s.nextSeq(), s.nextId());
        for (Resource r : Resource.values()) line(out, "stock", r, s.stock().get(r), s.capacity().get(r));
        s.storageDone().stream().sorted().forEach(r -> line(out, "storage", r));
        s.unlockedPlots().stream().sorted().forEach(p -> line(out, "plot", p));
        for (BuildingView b : s.buildings()) {
            line(out, "building", b.id(), b.kind(), b.plot(), b.stage(), b.paused(), b.pendingStage(), b.pendingFrom(),
                    b.activeFrom());
            if (b.last() != null) {
                StepReport.Row row = b.last();
                line(out, "report", b.id(), row.status(), row.startingUp(), row.produced(), row.consumed());
            }
        }
        for (PendingCommand p : s.pending()) {
            List<Object> fields = new ArrayList<>(List.of(p.at(), p.seq()));
            fields.add(p.requestId() == null ? NONE : p.requestId());
            fields.add(p.expectedStage());
            fields.addAll(commandFields(p.command()));
            line(out, "pending", fields.toArray());
        }
        s.requests().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            RequestRecord r = e.getValue();
            line(out, "request", e.getKey(), r.status(), r.rejection());
        });
        return out.toString();
    }

    /** Liest den Text. Jede Abweichung von der Form wirft {@link EconomyException} mit Reason INVALID_SNAPSHOT. */
    public static EconomySnapshot decode(String text) {
        String[] lines = text.split("\n");
        if (lines.length == 0 || !lines[0].startsWith(VERSION_PREFIX)) throw invalid("Kopfzeile fehlt");
        if (!lines[0].equals(HEADER)) throw invalid("Schemaversion unbekannt: " + lines[0]);

        Map<String, String[]> singles = new HashMap<>();
        Map<Resource, Long> stock = new EnumMap<>(Resource.class);
        Map<Resource, Long> capacity = new EnumMap<>(Resource.class);
        Set<Resource> storageDone = EnumSet.noneOf(Resource.class);
        Set<Plot> plots = EnumSet.noneOf(Plot.class);
        List<String[]> buildingLines = new ArrayList<>();
        Map<Integer, StepReport.Row> reports = new HashMap<>();
        List<PendingCommand> pending = new ArrayList<>();
        Map<UUID, RequestRecord> requests = new HashMap<>();

        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split(" ", -1);
            if (SINGLE_FIELDS.containsKey(f[0])) {
                need(f, SINGLE_FIELDS.get(f[0]));
                if (singles.put(f[0], f) != null) throw invalid("doppelter Eintrag " + f[0]);
                continue;
            }
            switch (f[0]) {
                case "stock" -> {
                    need(f, 4);
                    Resource r = enumValue(Resource.class, f[1]);
                    if (stock.put(r, number(f[2])) != null) throw invalid("doppelter Bestand " + r);
                    capacity.put(r, number(f[3]));
                }
                case "storage" -> {
                    need(f, 2);
                    if (!storageDone.add(enumValue(Resource.class, f[1]))) throw invalid("doppelter Lagerausbau " + f[1]);
                }
                case "plot" -> {
                    need(f, 2);
                    if (!plots.add(enumValue(Plot.class, f[1]))) throw invalid("doppelter Bauplatz " + f[1]);
                }
                case "building" -> {
                    need(f, 9);
                    buildingLines.add(f);
                }
                case "report" -> {
                    need(f, 6);
                    StepReport.Row row = new StepReport.Row(integer(f[1]), enumValue(StepReport.Status.class, f[2]),
                            bool(f[3]), number(f[4]), number(f[5]));
                    if (reports.put(row.buildingId(), row) != null) throw invalid("doppelter Bericht " + f[1]);
                }
                case "pending" -> {
                    if (f.length < 6) throw invalid("Befehlszeile zu kurz");
                    pending.add(new PendingCommand(number(f[1]), number(f[2]), command(f, 5),
                            f[3].equals(NONE) ? null : uuid(f[3]), integer(f[4])));
                }
                case "request" -> {
                    need(f, 4);
                    UUID id = uuid(f[1]);
                    RequestStatus status = enumValue(RequestStatus.class, f[2]);
                    Rejection rejection = f[3].equals(NONE) ? null : enumValue(Rejection.class, f[3]);
                    if (requests.put(id, new RequestRecord(status, rejection)) != null) throw invalid("doppelter Auftrag " + id);
                }
                default -> throw invalid("unbekannter Eintrag " + f[0]);
            }
        }
        for (String key : SINGLE_FIELDS.keySet()) {
            if (!singles.containsKey(key)) throw invalid("Eintrag " + key + " fehlt");
        }
        if (stock.size() != Resource.values().length) throw invalid("Bestand fehlt für einen Rohstoff");

        List<BuildingView> buildings = new ArrayList<>();
        for (String[] f : buildingLines) {
            int id = integer(f[1]);
            buildings.add(new BuildingView(id, enumValue(BuildingKind.class, f[2]), enumValue(Plot.class, f[3]), integer(f[4]),
                    bool(f[5]), integer(f[6]), number(f[7]), number(f[8]), reports.remove(id)));
        }
        if (!reports.isEmpty()) throw invalid("Bericht ohne Betrieb");

        return new EconomySnapshot(
                uuid(singles.get("realm")[1]),
                number(singles.get("epoch")[1]),
                number(singles.get("anchor")[1]),
                number(singles.get("revision")[1]),
                stock,
                capacity,
                storageDone,
                plots,
                buildings,
                pending,
                number(singles.get("next")[1]),
                integer(singles.get("next")[2]),
                requests);
    }

    private static List<Object> commandFields(Command command) {
        return switch (command) {
            case Command.Build c -> List.of("build", c.kind(), c.plot());
            case Command.Upgrade c -> List.of("upgrade", c.buildingId());
            case Command.Pause c -> List.of("pause", c.buildingId());
            case Command.Resume c -> List.of("resume", c.buildingId());
            case Command.Move c -> List.of("move", c.buildingId(), c.plot());
            case Command.Storage c -> List.of("storage", c.resource());
            case Command.Expand c -> List.of("expand");
        };
    }

    /** Befehl ab Feldindex from; die Feldanzahl richtet sich nach der Befehlsart. */
    private static Command command(String[] f, int from) {
        return switch (f[from]) {
            case "build" -> {
                need(f, from + 3);
                yield new Command.Build(enumValue(BuildingKind.class, f[from + 1]), enumValue(Plot.class, f[from + 2]));
            }
            case "upgrade" -> {
                need(f, from + 2);
                yield new Command.Upgrade(integer(f[from + 1]));
            }
            case "pause" -> {
                need(f, from + 2);
                yield new Command.Pause(integer(f[from + 1]));
            }
            case "resume" -> {
                need(f, from + 2);
                yield new Command.Resume(integer(f[from + 1]));
            }
            case "move" -> {
                need(f, from + 3);
                yield new Command.Move(integer(f[from + 1]), enumValue(Plot.class, f[from + 2]));
            }
            case "storage" -> {
                need(f, from + 2);
                yield new Command.Storage(enumValue(Resource.class, f[from + 1]));
            }
            case "expand" -> {
                need(f, from + 1);
                yield new Command.Expand();
            }
            default -> throw invalid("unbekannter Befehl " + f[from]);
        };
    }

    private static void line(StringBuilder out, String key, Object... fields) {
        out.append(key);
        for (Object field : fields) {
            out.append(' ');
            out.append(field == null ? NONE : field instanceof Enum<?> e ? e.name() : field.toString());
        }
        out.append('\n');
    }

    private static void need(String[] f, int count) {
        if (f.length != count) throw invalid("Feldanzahl von " + f[0] + " falsch");
    }

    private static long number(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw invalid("keine Zahl: " + s);
        }
    }

    private static int integer(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw invalid("keine ganze Zahl: " + s);
        }
    }

    private static boolean bool(String s) {
        if (s.equals("true")) return true;
        if (s.equals("false")) return false;
        throw invalid("kein Wahrheitswert: " + s);
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw invalid("keine Kennung: " + s);
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String s) {
        try {
            return Enum.valueOf(type, s);
        } catch (IllegalArgumentException e) {
            throw invalid("unbekannter Wert " + s);
        }
    }

    private static EconomyException invalid(String detail) {
        return new EconomyException(Reason.INVALID_SNAPSHOT, detail);
    }
}
