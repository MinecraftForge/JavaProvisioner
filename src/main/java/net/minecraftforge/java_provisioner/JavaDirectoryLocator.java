/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.java_provisioner;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import net.minecraftforge.java_provisioner.api.JavaInstall;
import net.minecraftforge.java_provisioner.api.JavaProvisionerException;
import net.minecraftforge.util.os.OS;

/*
 * Attempts to find the java install from specific folders.
 * Will search the folder, and immediate sub-folders.
 */
final class JavaDirectoryLocator extends JavaHomeLocator {
    private final Collection<File> paths;

    private static Collection<File> guesses() {
        File userHome = new File(System.getProperty("user.home"));

        Collection<File> ret = new ArrayList<>();
        if (OS.current() == OS.WINDOWS) { // Windows
            for (File root : File.listRoots()) {
                ret.add(new File(root, "Program Files\\Java"));
                if (Disco.Arch.CURRENT.is64Bit())
                    ret.add(new File(root, "Program Files (x86)\\Java"));
            }
        } else if (OS.current() == OS.MACOS) { // Mac
            ret.add(new File("/Library/Java/JavaVirtualMachines"));
        } else { // Linux
            ret.add(new File("/usr/java"));
            ret.add(new File("/usr/lib/jvm"));
            ret.add(new File("/usr/lib64/jvm"));
            ret.add(new File("/usr/local/"));
            ret.add(new File("/opt"));
            ret.add(new File("/app/jdk"));
            ret.add(new File("/opt/jdk"));
            ret.add(new File("/opt/jdks"));
        }

        // IntelliJ
        ret.add(new File(userHome, OS.current() == OS.MACOS ? "/Library/Java/JavaVirtualMachines" : ".jdks"));

        // JABBA
        ret.add(new File(userHome, ".jabba/jdks"));
        String JABBA_HOME = System.getenv("JABBA_HOME");
        if (JABBA_HOME != null)
            ret.add(new File(new File(JABBA_HOME), "jdks"));

        // SDKMan
        ret.add(new File(userHome, ".sdkman/candidates/java"));

        // ASDF
        ret.add(new File(userHome, ".asdf/installs/java"));
        String ASDF_DATA_DIR = System.getenv("ASDF_DATA_DIR");
        if (ASDF_DATA_DIR != null)
            ret.add(new File(new File(ASDF_DATA_DIR), "installs/java"));

        ret.removeIf(f -> !f.exists() || !f.isDirectory());

        return ret;
    }

    public JavaDirectoryLocator() {
        this(guesses());
    }

    public JavaDirectoryLocator(Collection<File> paths) {
        this.paths = expand(paths);
    }

    private Collection<File> expand(Collection<File> files) {
        Collection<File> ret = new ArrayList<>();
        String exe = "bin/java" + OS.current().exe();
        for (File file : files) {
            if (new File(file, exe).exists())
                ret.add(file);
            else {
                File[] subFiles = file.listFiles();
                if (subFiles != null) {
                    for (File subFile : subFiles) {
                        if (subFile.isDirectory() && new File(subFile, exe).exists())
                            ret.add(subFile);
                    }
                }
            }
        }
        return ret;
    }

    @Override
    public File find(int version) throws JavaProvisionerException {
        for (File path : paths) {
            JavaInstall install = fromPath(path, version);
            if (install != null)
                return install.home();
        }

        throw new JavaProvisionerException(String.format("Failed to find any Java installations from paths: %s", this.paths.stream().map(File::getPath).collect(Collectors.joining(", "))), logOutput());
    }

    @Override
    public List<JavaInstall> findAll(int version) {
        List<JavaInstall> ret = new ArrayList<>();

        for (File path : paths) {
            JavaInstall install = fromPath(path, version);
            if (install != null)
                ret.add(install);
        }

        if (ret.isEmpty())
            log(String.format("Failed to find any Java installations from paths: %s", this.paths.stream().map(File::getPath).collect(Collectors.joining(", "))));

        return ret;
    }
}
