/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.java_provisioner.api;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public enum Distro implements Comparable<Distro> {
    // Preferred Distros
    MICROSOFT,       // Used by Minecraft
    TEMURIN,         // Trusted build of OpenJDK by Eclipse Adoptium
    ZULU,            // next best from Zulu, has macOS ARM support for Java 8

    // Highlights from the others
    JETBRAINS, // DCEVM, has optimizations for swing, but is otherwise standard
    CORRETTO,  // Amazon, has optimizations for containers/cloud

    // Vanilla OpenJDK -- Windows uses older builds, outdated builds from AdoptOpenJDK, unfortunately.
    ORACLE_OPEN_JDK,

    // Everything else
    DEBIAN,
    BISHENG,
    DRAGONWELL,
    KONA,
    LIBERICA,
    LIBERICA_NATIVE,
    MANDREL,
    OPENLOGIC,
    REDHAT,
    SAP_MACHINE,

    // Has weird licensing. Prefer less.
    ZULU_PRIME, // Free for development use, but see https://www.azul.com/wp-content/uploads/Azul-Platform-Prime-Evaluation-Agreement.pdf
    ORACLE,     // Free for 21+, sketch for older. Avoid using. See https://www.oracle.com/java/technologies/javase/jdk-faqs.html
    GRAALVM,    // Similar to standard Oracle Java SE. See https://www.oracle.com/downloads/licenses/graal-free-license.html

    // Builds of GraalVM. Likely TCK certified, but not standard builds of OpenJDK
    GRAALVM_COMMUNITY,
    GLUON_GRAALVM,     // Fork of GraalVM Community, not of the standard GraalVM from Oracle

    // Unmaintained. Avoid.
    AOJ,          // what Temurin used to be
    OJDK_BUILD,   // what Red Hat used to be
    TRAVA,        // what JetBrains used to be
    GRAALVM_CE8,
    GRAALVM_CE11,
    GRAALVM_CE17,
    GRAALVM_CE19,
    GRAALVM_CE16,

    // OpenJ9 implementations, avoid if at all possible
    SEMERU,           // Non-hotspot JVM, has weird quirks with reflection.
    AOJ_OPENJ9,       // Unmaintained; what Semeru used to be
    SEMERU_CERTIFIED; // IBM-licensed. See https://www.ibm.com/support/pages/ibm-semeru-java-certified-edition-versus-open-edition

    private static final Distro[] $values = values();
    private final String key;

    Distro() {
        this.key = this.name().toLowerCase(Locale.ENGLISH);
    }

    public String key() {
        return this.key;
    }

    public static @Nullable Distro byKey(String key) {
        for (Distro value : $values) {
            if (value.key.equals(key))
                return value;
        }

        return null;
    }
}
