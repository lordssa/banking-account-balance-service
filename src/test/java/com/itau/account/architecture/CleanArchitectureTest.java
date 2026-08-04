package com.itau.account.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.itau.account", importOptions = ImportOption.DoNotIncludeTests.class)
class CleanArchitectureTest {

    @ArchTest
    static final ArchRule domainMustNotDependOnFrameworks = noClasses()
            .that().resideInAPackage("com.itau.account.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "software.amazon..",
                    "jakarta.servlet..",
                    "com.fasterxml.jackson..",
                    "tools.jackson..",
                    "org.springframework.jdbc.."
            );

    @ArchTest
    static final ArchRule applicationMustNotDependOnAdapters = noClasses()
            .that().resideInAPackage("com.itau.account.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.itau.account.adapter..",
                    "com.itau.account.bootstrap..",
                    "org.springframework..",
                    "software.amazon..",
                    "com.fasterxml.jackson..",
                    "tools.jackson.."
            );
}
