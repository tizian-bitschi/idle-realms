package idle_realms.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "idle_realms")
class CoreBoundaryTest {
    @ArchTest
    static final ArchRule core_does_not_depend_on_minecraft = noClasses()
            .that().resideInAPackage("idle_realms.core..")
            .should().dependOnClassesThat().resideInAnyPackage("net.minecraft..", "net.neoforged..");
}
