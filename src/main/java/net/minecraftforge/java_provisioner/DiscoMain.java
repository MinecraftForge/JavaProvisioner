/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.java_provisioner;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.EnumConverter;
import net.minecraftforge.java_provisioner.api.Distro;
import net.minecraftforge.util.os.OS;
import org.jetbrains.annotations.VisibleForTesting;

@VisibleForTesting
public final class DiscoMain {
    private DiscoMain() { }

    public static void main(String[] args) throws Exception {
        OptionParser parser = new OptionParser();
        parser.allowsUnrecognizedOptions();
        OptionSpec<Void> helpO = parser.accepts("help", "Displays this help message and exits");

        parser.accepts("disco-main", "Use the DiscoMain entry point");

        OptionSpec<File> cacheO = parser.accepts("cache",
                "Directory to store data needed for this program")
                .withRequiredArg().ofType(File.class).defaultsTo(new File("cache"));

        OptionSpec<Void> downloadJdkO = parser.accepts("download-jdk", "Download an extracts a JDK");
        OptionSpec<Integer> javeVersionO = parser.accepts("java-version",
                "Major version of java to download, will attempt the highest version avalible if unspecified")
                .withOptionalArg().ofType(Integer.class);
        OptionSpec<Disco.Arch> archO = parser.acceptsAll(l("arch", "architecture"),
                "Architecture for use in Disco api")
                .withRequiredArg().withValuesConvertedBy(converter(Disco.Arch.class)).defaultsTo(Disco.Arch.CURRENT);
        OptionSpec<OS> osO = parser.acceptsAll(l("os", "operating-system"),
                "Operating System for use in Disco api")
                .withRequiredArg().withValuesConvertedBy(converter(OS.class)).defaultsTo(OS.current());
        OptionSpec<Distro> distroO = parser.acceptsAll(l("distro", "distribution"),
                "Distribution for use in Disco api")
                                           .withRequiredArg().withValuesConvertedBy(converter(Distro.class)).defaultsTo(Distro.MICROSOFT);
        OptionSpec<Void> autoO = parser.accepts("auto",
                "Auto select a JDK to download without prompting if there are multiple options");

        OptionSet options = parser.parse(args);

        // File gradle = new File(System.getProperty("user.home", "."), ".gradle/jdks/");
        File cache = cacheO.value(options);

        boolean success = true;
        if (options.has(helpO)) {
            parser.printHelpOn(System.out);
        } else if (options.has(downloadJdkO)) {
            success = downloadJdk(
                options.hasArgument(javeVersionO) ? javeVersionO.value(options) : -1,
                archO.value(options),
                osO.value(options),
                distroO.value(options),
                options.has(autoO),
                cache
            );
        } else {
            parser.printHelpOn(System.out);
        }

        if (!success)
            System.exit(1);
    }

    private static List<String> l(String...strings) {
        return Arrays.asList(strings);
    }

    private static boolean downloadJdk(
        int javaVersion, Disco.Arch arch, OS os, Distro distro,
        boolean auto, File cache
    ) {
        if (arch == Disco.Arch.UNKNOWN) {
            arch = Disco.Arch.X64;
            System.out.println("Unknown Architecture (" + System.getProperty("os.arch") + ")" +
                " Defaulting to " + arch.name() + "." +
                " Use --arch to specify an alternative.");
        }

        if (os == OS.UNKNOWN) {
            os = OS.LINUX;
            System.out.println("Unknown Operating System (" + System.getProperty("os.name") + ")" +
                " Defaulting to " + os.name() + "." +
                " Use --os to specify an alternative.");
        }

        System.out.println("Downloading JDK:");
        System.out.println("  Version: " + (javaVersion == -1 ? "latest" : javaVersion));
        System.out.println("  Arch:    " + (arch   == null ? "null" : arch  .name()));
        System.out.println("  OS:      " + (os     == null ? "null" : os    .name()));
        System.out.println("  Distro:  " + (distro == null ? "null" : distro.name()));
        System.out.println("  Cache:   " + cache.getAbsolutePath());
        Disco disco = new Disco(new File(cache, "jdks"));

        List<Disco.Package> jdks = disco.getPackages(javaVersion, os, distro, arch);
        if (jdks.isEmpty()) {
            System.err.println("Failed to find any download, try specifying a different java version or distro");
            return false;
        }

        Disco.Package pkg;
        if (jdks.size() == 1 || auto) {
            pkg = jdks.get(0);
        } else {
            for (int x = 0; x < jdks.size(); x++) {
                Disco.Package jdk = jdks.get(x);
                System.out.printf("[%2d] %s: %s%n", x+1, jdk.distribution, jdk.filename);
                //log(Disco.GSON.toJson(jdk));
            }

            @SuppressWarnings("resource")
            Scanner scan = new Scanner(System.in);
            System.out.print("Select Download: ");
            String line = scan.nextLine();

            int selected = -1;
            try {
                selected = Integer.parseInt(line);
            } catch (NumberFormatException e) {
                System.err.println("Invalid selection \"" + line + "\" is not a number.");
            }

            if (selected <= 0 || selected > jdks.size()) {
                System.err.println("Invalid selection, must be between 1 and " + jdks.size());
                return false;
            } else
                pkg = jdks.get(selected - 1);
        }

        System.out.println();

        File java_home;
        try {
            java_home = disco.extract(pkg);
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract downloaded package", e);
        }

        // No longer needed as we throw if no java home, but just in case:
        //if (java_home == null)
        //    System.exit(1);

        ProcessUtils.Result result = ProcessUtils.testJdk(java_home);
        if (result.exitCode != 0) {
            System.err.println("Failed to run extracted java:");
            for (String line : result.lines)
                System.err.println("  " + line);

            return false;
        }

        return true;
    }

    private static <T extends Enum<T>> EnumConverter<T> converter(Class<T> clazz) {
        return new EnumConverter<T>(clazz) {
            @Override
            public T convert(String value) {
                if ("null".equals(value))
                    return null;
                return super.convert(value);
            }
        };
    }}
