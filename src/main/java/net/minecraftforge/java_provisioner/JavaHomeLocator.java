/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.java_provisioner;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.java_provisioner.api.JavaInstall;
import net.minecraftforge.java_provisioner.api.JavaLocator;
import net.minecraftforge.java_provisioner.api.JavaProvisionerException;
import net.minecraftforge.util.os.OS;
import org.jetbrains.annotations.Nullable;

/*
 * Attempts to find the java install using the JAVA_HOME environment variable.
 * It also searches for some common extras, just in case they are set.
 *
 * Github Actions:
 *   The default images ship Java 8, 11, 17, and 21. In the format JAVA_HOME_{version}_{x64|arm64}
 *     https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md
 *     https://github.com/actions/runner-images/blob/main/images/windows/Windows2025-Readme.md
 *     https://github.com/actions/runner-images/blob/main/images/macos/macos-15-Readme.md
 *     https://github.com/actions/runner-images/blob/main/images/macos/macos-26-arm64-Readme.md
 *
 * Lex's Personal Setup:
 *   JAVA_HOME_{version}
 *   I have used this format for years, kinda thought GitHub would use it, but they append the
 *   architecture for legacy reasons. It just makes life easier.
 */
class JavaHomeLocator implements JavaLocator {
    protected List<String> searched = new ArrayList<>();

    @Override
    public File find(int version) throws JavaProvisionerException {
        // For GitHub actions
        String suffix = Disco.Arch.CURRENT.is64Bit()
            ? (Disco.Arch.CURRENT.isArm() ? "_arm64" : "_X64")
            : "";
        JavaInstall ret = fromEnv("JAVA_HOME_" + version + suffix, version);
        if (ret != null)
            return ret.home();

        // For Lex's Personal Setup
        ret = fromEnv("JAVA_HOME_" + version, version);
        if (ret != null)
            return ret.home();

        // Standard Java Home
        ret = fromEnv("JAVA_HOME", version);
        if (ret != null)
            return ret.home();

        // Any other JAVA_HOME variables
        for (String key : System.getenv().keySet()) {
            if (key.startsWith("JAVA_HOME")) {
                ret = fromEnv("JAVA_HOME", version);
                if (ret != null)
                    return ret.home();
            }
        }

        throw new JavaProvisionerException("Failed to find any Java installations from JAVA_HOME environment variables", logOutput());
    }

    @Override
    public List<JavaInstall> findAll(int version) {
        // Not a set, but keeps the preferred order of JAVA_HOME variables
        List<String> keys = new ArrayList<String>() {
            @Override
            public boolean add(String o) {
                return !this.contains(o) && super.add(o);
            }
        };

        if (version >= 0) {
            // For GitHub actions
            String suffix = Disco.Arch.CURRENT.is64Bit()
                ? (Disco.Arch.CURRENT.isArm() ? "_arm64" : "_X64")
                : "";
            keys.add("JAVA_HOME_" + version + suffix);

            // For Lex's Personal Setup
            keys.add("JAVA_HOME_" + version);
        }

        // Standard Java Home
        keys.add("JAVA_HOME");

        // Any other JAVA_HOME variables
        for (String key : System.getenv().keySet()) {
            if (key.startsWith("JAVA_HOME")) {
                keys.add(key);
            }
        }

        List<JavaInstall> ret = new ArrayList<>();
        for (String key : keys) {
            JavaInstall install = fromEnv(key, version);
            if (install != null)
                ret.add(install);
        }

        if (ret.isEmpty())
            log("Failed to find any Java installations from JAVA_HOME environment variables");

        return ret;
    }

    @Override
    public List<String> logOutput() {
        return this.searched;
    }

    protected void log(String line) {
        searched.add(line);
    }

    @Nullable JavaInstall fromEnv(String name, int version) {
        if (version < 0)
            return fromEnv(name);

        JavaInstall result = fromEnv(name);
        if (result == null)
            return null;

        if (result.majorVersion() != version) {
            log("  Wrong version: Was " + result.majorVersion() + " wanted " + version);
            return null;
        }

        return result;
    }

    @Nullable JavaInstall fromEnv(String name) {
        String env = System.getenv(name);
        if (env == null) {
            log("Environment: \"" + name + "\" Empty");
            return null;
        }

        log("Environment: \"" + name + "\"");
        log("  Value: \"" + env + "\"");
        return fromPath(env);
    }

    @Nullable JavaInstall fromPath(String path) {
        return fromPath(new File(path));
    }

    @Nullable JavaInstall fromPath(File path) {
        File exe = new File(path, "bin/java" + OS.current().exe());

        if (!exe.exists()) {
            log("  Missing Executable: " + exe);
            return null;
        }

        ProcessUtils.ProbeResult result = ProcessUtils.testJdk(path);
        if (result.exitCode != 0) {
            log("  Probe failed with exit code: " + result.exitCode);
            for (String line : result.lines)
                searched.add("    " + line);
        }

        return result.meta;
    }

    @Nullable JavaInstall fromPath(String path, int version) {
        return fromPath(new File(path), version);
    }

    @Nullable JavaInstall fromPath(File path, int version) {
        if (version < 0)
            return fromPath(path);

        JavaInstall result = fromPath(path);
        if (result == null)
            return null;

        if (result.majorVersion() != version) {
            log("  Wrong version: Was " + result.majorVersion() + " wanted " + version);
            return null;
        }

        return result;
    }
}
