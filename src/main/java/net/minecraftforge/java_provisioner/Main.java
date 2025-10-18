/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.java_provisioner;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import joptsimple.AbstractOptionSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import net.minecraftforge.java_provisioner.api.Distro;
import net.minecraftforge.java_provisioner.api.JavaInstall;
import net.minecraftforge.java_provisioner.api.JavaLocator;
import net.minecraftforge.util.os.OS;
import org.jetbrains.annotations.VisibleForTesting;

@VisibleForTesting
public final class Main {
    private Main() { }

    public static void main(String[] args) throws Exception {
        OptionParser parser = new OptionParser();

        if (hasArgument(args, "--disco-main")) {
            DiscoMain.main(args);
            return;
        }

        OptionSpec<Void> helpO = parser.accepts("help", "Displays this help message and exits");
        parser.accepts("disco-main", "Use the DiscoMain entry point");

        OptionSpec<File> cacheO = parser.accepts("cache",
                "Directory to store data needed for this program")
                .withRequiredArg().ofType(File.class).defaultsTo(new File("cache"));

        AbstractOptionSpec<Void> offlineO = parser.accepts("offline",
                "Do not attempt to download any JDKs, only use the cache");

        OptionSpec<Integer> versionO = parser.accepts("version",
                "Major version of java to try and locate")
                .withRequiredArg().ofType(Integer.class);

        OptionSpec<Void> allO = parser.accepts("all",
                "Display information about all detected java installs");

        OptionSpec<Void> testO = parser.accepts("test", "Enable test functionality, provisioning a bunch of jdks.");

        OptionSet options = parser.parse(args);
        if (options.has(helpO)) {
            parser.printHelpOn(System.out);
            return;
        }
        File cache = options.valueOf(cacheO);
        DiscoLocator disco = new DiscoLocator(cache, options.has(offlineO));

        List<JavaLocator> locators = new ArrayList<>();
        locators.add(new JavaHomeLocator());
        locators.add(new GradleLocator());
        locators.add(new JavaDirectoryLocator());
        locators.add(disco);

        if (options.has(testO)) {
            // populate downloaded for testing
            Disco tmp = new Disco(cache);
            int version = options.has(versionO) ? options.valueOf(versionO) : 22;
            for (Distro dist : new Distro[] { Distro.TEMURIN, Distro.AOJ, Distro.ORACLE, Distro.ZULU, Distro.GRAALVM, Distro.GRAALVM_COMMUNITY }) {
                List<Disco.Package> jdks = tmp.getPackages(version, OS.current(), dist, Disco.Arch.CURRENT);
                int seen = 0;
                for (Disco.Package pkg : jdks) {
                    if (seen++ < 3)
                        tmp.extract(pkg);
                }
            }
        }

        if (options.has(allO)) {
            listAllJavaInstalls(locators);
        } else if (options.has(versionO)) {
            int version = options.valueOf(versionO);
            findSpecificVersion(locators, disco, version);
        } else {
            System.err.println("You must specify a version to search for using --version or --all to list all java installs.");
            parser.printHelpOn(System.out);
            System.exit(-1);
        }
    }

    private static boolean hasArgument(String[] args, String arg) {
        for (String s : args) {
            if (s.toLowerCase(Locale.ENGLISH).startsWith(arg))
                return true;
        }

        return false;
    }

    private static void findSpecificVersion(List<JavaLocator> locators, DiscoLocator disco, int version) {
        File result = null;
        for (JavaLocator locator : locators) {
            try {
                result = locator.find(version);
                break;
            } catch (Exception e) {
                continue;
            }
        }

        // Could not find it with a locator, let's try downloading it.
        System.out.println("Locators failed to find any suitable installs, attempting Disco download");
        Throwable error = null;
        if (result == null) {
            try {
                result = disco.provision(version).home();
            } catch (Exception e) {
                error = e;
            }
        }

        if (result != null && result.exists()) {
            String home = result.getAbsolutePath();
            if (!home.endsWith(File.separator))
                home += File.separatorChar;
            System.out.println(home);
        } else {
            System.err.println("Failed to find sutable java for version " + version);
            for (JavaLocator locator : locators) {
                System.err.println("Locator: " + locator.getClass().getSimpleName());
                for (String line : locator.logOutput()) {
                    System.err.println("  " + line);
                }
            }
            if (error != null) {
                error.printStackTrace(System.err);
            }
            System.exit(1);
        }
    }

    private static void listAllJavaInstalls(List<JavaLocator> locators) {
        List<JavaInstall> installs = new ArrayList<>();

        for (JavaLocator locator : locators) {
            List<JavaInstall> found = locator.findAll();
            installs.addAll(found);
        }

        // Remove duplicates
        Set<File> seen = new HashSet<>();
        for (Iterator<JavaInstall> itr = installs.iterator(); itr.hasNext(); ) {
            JavaInstall install = itr.next();
            if (!seen.add(install.home()))
                itr.remove();
        }

        Collections.sort(installs);

        for (JavaInstall install : installs) {
            System.out.println(install.home().getAbsolutePath());
            System.out.println("  Vendor:  " + install.vendor());
            System.out.println("  Type:    " + (install.isJdk() ? "JDK" : "JRE"));
            System.out.println("  Version: " + install.version());
        }

    }
}
