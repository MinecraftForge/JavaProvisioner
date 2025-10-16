/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.java_provisioner;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.minecraftforge.java_provisioner.Disco.Arch;
import net.minecraftforge.java_provisioner.api.IJavaInstall;
import net.minecraftforge.java_provisioner.util.OS;

/*
 * Attempts to find the java install from specific folders.
 * Will search the folder, and immediate sub-folders.
 */
public class JavaDirectoryLocator extends JavaHomeLocator {
    private final Collection<File> paths;

    private static Collection<File> guesses() {
        Collection<File> ret = new ArrayList<>();
        if (OS.CURRENT == OS.WINDOWS) { // Windows
            File[] roots = File.listRoots();
            for(int i = 0; i < roots.length ; i++) {
                ret.add(new File(roots[i], "Program Files\\Java"));
                if (Arch.CURRENT.is64Bit())
                    ret.add(new File(roots[i], "Program Files (x86)\\Java"));
            }
        } else if (OS.CURRENT == OS.OSX) { // Mac
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
        String exe = "bin/java" + OS.CURRENT.exe();
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
    public File find(int version) {
        for (File path : paths) {
            IJavaInstall result = fromPath(path);
            if (result != null && result.majorVersion() == version)
                return result.home();
        }
        return null;
    }

    @Override
    public List<IJavaInstall> findAll() {
        List<IJavaInstall> ret = new ArrayList<>();
        for (File path : paths) {
            IJavaInstall result = fromPath(path);
            if (result != null)
                ret.add(result);
        }
        return ret;
    }
}
