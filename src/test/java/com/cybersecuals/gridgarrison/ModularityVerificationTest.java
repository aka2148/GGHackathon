package com.cybersecuals.gridgarrison;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Verifies Spring Modulith module boundaries at build time.
 *
 * This test will FAIL if any module accesses package-private internals of
 * another module, creating an illegal dependency.
 *
 * Run with: ./mvnw test -Dtest=ModularityVerificationTest
 */
class ModularityVerificationTest {

    private static final ApplicationModules modules =
        ApplicationModules.of(GridGarrisonApplication.class);

    @Test
    void moduleStructureIsValid() {
        // Asserts no illegal cross-module dependencies exist
        modules.verify();
    }

    @Test
    void generateModuleDocumentation() {
        // Generates PlantUML + Asciidoc module diagrams in target/modulith-docs/
        new Documenter(modules)
            .writeModulesAsPlantUml()
            .writeIndividualModulesAsPlantUml();
    }
}
