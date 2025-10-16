/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.java_provisioner.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraftforge.java_provisioner.api.IJavaInstall;

public class ProcessUtils {
    public static class Result {
        public final List<String> lines;
        public final int exitCode;
        private Result(List<String> lines, int exitCode) {
            this.lines = Collections.unmodifiableList(lines);
            this.exitCode = exitCode;
        }
    }

    public static class ProbeResult extends Result {
        public final IJavaInstall meta;

        private ProbeResult(File root, Result parent) {
            this(root, parent.exitCode, parent.lines);
        }

        private ProbeResult(File root, int exitCode, List<String> lines) {
            super(lines, exitCode);

            if (exitCode != 0) {
                this.meta = null;
                return;
            }

            Map<String, String> probe = new HashMap<>();

            for (String line : lines) {
                if (!line.startsWith("JAVA_PROBE: "))
                    continue;
                int idx = line.indexOf(' ', 12);
                if (idx == -1)
                    continue;
                String key = line.substring(12, idx);
                String value = line.substring(idx + 1);
                probe.put(key, value);
            }

            String version = get(probe, "java.version", "java.runtime.version", "java.vm.version");
            String vendor = get(probe, "java.vendor", "java.vm.vendor");
            this.meta = new JavaInstall(root, version, vendor);

        }

        private static String get(Map<String, String> props, String... names) {
            for (String name : names) {
                String ret = props.get(name);
                if (ret != null && !"unset".equals(name))
                    return ret;
            }
            return null;
        }
    }

    private static String getStackTrace(Throwable t) {
        StringWriter string = new StringWriter();
        t.printStackTrace(new PrintWriter(string, true));
        return string.toString();
    }

    private static void getStackTrace(Throwable t, Collection<String> lines) {
        String[] stack = getStackTrace(t).split("\r?\n");
        for (String line : stack)
            lines.add(line);
    }

    public static Result runCommand(String...args) {
        List<String> lines = new ArrayList<>();
        int exitCode = runCommand(lines, args);
        return new Result(lines, exitCode);
    }

    public static int runCommand(List<String> lines, String... args) {
        Process process;
        try {
            process = new ProcessBuilder(args)
                .redirectErrorStream(true)
                .start();
        } catch (IOException e) {
            getStackTrace(e, lines);
            return -1;
        }

        BufferedReader is = new BufferedReader(new InputStreamReader(process.getInputStream()));

        try {
            while (process.isAlive() || is.ready()) {
                String line = is.readLine();
                if (line != null)
                    lines.add(line);
            }
        } catch (IOException e) {
            getStackTrace(e, lines);
            process.destroy();
            return -2;
        }

        return process.exitValue();
    }

    protected static Path getPathFromResource(String resource) {
        return getPathFromResource(resource, ProcessUtils.class.getClassLoader());
    }

    protected static Path getPathFromResource(String resource, ClassLoader cl) {
        URL url = cl.getResource(resource);
        if (url == null)
            throw new IllegalStateException("Could not find " + resource + " in classloader " + cl);

        String str = url.toString();
        int len = resource.length();
        if ("jar".equalsIgnoreCase(url.getProtocol())) {
            str = url.getFile();
            len += 2;
        }
        str = str.substring(0, str.length() - len);
        return Paths.get(URI.create(str));
    }

    // Have to make this not a static string, so the shadow plugin doesn't rewrite it.
    private static final String JAVA_PROBE = "JavaProbe" + ".class".toLowerCase(Locale.ENGLISH);

    public static ProbeResult testJdk(File java_home) {
        File probe = getPathFromResource(JAVA_PROBE).toFile();
        String classpath = probe.getAbsolutePath();
        File exe = new File(java_home, "bin/java" + OS.CURRENT.exe());

        if (!exe.exists())
            return new ProbeResult(java_home, -1, Collections.singletonList("missing java executable"));

        // Some old jvms require manually adding the classes zip, so lets add it if it exists
        File classes = new File(java_home, "libs/classes.zip");
        if (classes.exists())
            classpath += File.pathSeparator + classes.getAbsolutePath();

        Result ret = runCommand(exe.getAbsolutePath(), "-classpath", classpath, "JavaProbe");
        return new ProbeResult(java_home, ret);
    }
}
