/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.java_provisioner.api;

import java.util.regex.Pattern;

class Util {
    // NOTE: JVM vendors do not need to make the vendor name the same as the Disco distro.
    //       These are our best guesses for preferred vendor order
    private static final Pattern[] SORTED_VENDORS = new Pattern[] {
        Pattern.compile("microsoft", Pattern.CASE_INSENSITIVE),
        Pattern.compile("openjdk", Pattern.CASE_INSENSITIVE),
        Pattern.compile("temurin|adoptium|eclipse foundation", Pattern.CASE_INSENSITIVE),
        Pattern.compile("azul systems", Pattern.CASE_INSENSITIVE)
    };

    static int getVendorOrder(String vendor) {
        for (int x = 0; x < SORTED_VENDORS.length; x++) {
            if (SORTED_VENDORS[x].matcher(vendor).find())
                return x;
        }
        return -1;
    }
}
