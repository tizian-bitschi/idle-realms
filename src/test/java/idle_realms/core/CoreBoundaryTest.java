package idle_realms.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "idle_realms")
class CoreBoundaryTest {
    // shortcut: passes vacuously until the first class in idle_realms.core exists, upgrade by adding that class to this test's scope
    @ArchTest
    static final ArchRule core_does_not_depend_on_minecraft = noClasses()
            .that().resideInAPackage("idle_realms.core..")
            .should().dependOnClassesThat().resideInAnyPackage("net.minecraft..", "net.neoforged..")
            .allowEmptyShould(true);
}
