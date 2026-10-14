package idle_realms.server;

import java.io.PrintWriter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * Startet eine Phase des Prozessneustarts in einer eigenen JVM. Die JUnit-Sitzung bootet die NeoForge-Laufzeit, wie in der
 * Unit-Test-Umgebung. Aufruf: {@code write|read <Weltordner>}; der Exitcode ist 0, wenn die Phase besteht.
 */
public final class ProcessRestartDriver {
    private ProcessRestartDriver() {}

    public static void main(String[] args) {
        String phase = args[0];
        System.setProperty("idle_realms.phase", phase);
        System.setProperty("idle_realms.world", args[1]);
        String method = phase.equals("write") ? "writePhase" : "readPhase";
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectMethod(ProcessRestartTest.class, method))
                .build();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        // Die NeoForge-Laufzeit startet als LauncherSessionListener; ohne offene Sitzung fehlt sie.
        try (LauncherSession session = LauncherFactory.openSession()) {
            session.getLauncher().execute(request, listener);
        }
        TestExecutionSummary summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out, true));
        summary.printFailuresTo(new PrintWriter(System.err, true), 20);
        System.exit(summary.getTestsSucceededCount() == 1 && summary.getTotalFailureCount() == 0 ? 0 : 1);
    }
}
