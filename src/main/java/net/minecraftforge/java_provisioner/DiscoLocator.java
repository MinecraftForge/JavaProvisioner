/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.java_provisioner;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraftforge.java_provisioner.Disco.Arch;
import net.minecraftforge.java_provisioner.Disco.Distro;
import net.minecraftforge.java_provisioner.api.IJavaInstall;
import net.minecraftforge.java_provisioner.util.OS;

/**
 * Locates java installs that have been downloaded from the <a href="https://github.com/foojayio/discoapi">disco API</a>
 * by this program. This does NOT actually download anything. It is just a scanner for already existing installs.
 * <p>
 * Folder format is fairly straight forward, the package filename is the archive name from
 * 'package info' the api returns. I could use the package ID to get unique names, however
 * I thought using the filename was unique enough and provided more human readable names.
 * <p>
 * Either way that is not important, as when scanning all that matters is that the cache directory
 * has directories underneath it. And those directories are java homes.
 * <p>
 * There is one thing to note. I do not currently use any form of marker system or file locking.
 * This means that if you use the same cache across multiple processes this may cause weird states.
 * <p>
 * If this becomes and issue {it hasn't in the 10 years that FG has not given a fuck} then I can re-address this.
 */
public class DiscoLocator extends JavaHomeLocator {
    private final File cache;
    private final boolean offline;

    public DiscoLocator(File cache) {
        this(cache, false);
    }

    public DiscoLocator(File cache, boolean offline) {
        this.cache = cache;
        this.offline = offline;
    }

    @Override
    public File find(int version) {
        List<IJavaInstall> results = findInternal(version);
        return results.isEmpty() ? null : results.get(0).home();
    }

    @Override
    public List<IJavaInstall> findAll() {
        return findInternal(-1);
    }

    private List<IJavaInstall> findInternal(int version) {
        if (!cache.exists() || !cache.isDirectory())
            return Collections.emptyList();

        List<IJavaInstall> results = new ArrayList<>();
        for (File dir : cache.listFiles()) {
            if (!dir.isDirectory())
                continue;

            log("Disco Cache: \"" + dir.getAbsolutePath() + "\"");

            IJavaInstall ret = fromPath(dir);
            if (ret != null) {
                if (version == -1) {
                    results.add(ret);
                } else if (ret.majorVersion() != version) {
                    log("  Wrong version: Was " + ret.majorVersion() + " wanted " + version);
                } else {
                    results.add(ret);
                    return results;
                }
            }
        }

        return results;
    }

    @Override
    public IJavaInstall provision(int version) {
        log("Locators failed to find any suitable installs, attempting Disco download");
        Disco disco = new Disco(cache, offline) { // TODO: [DISCO][Logging] Add a proper logging handler sometime
            @Override
            protected void debug(String message) {
                DiscoLocator.this.log(message);
            }

            @Override
            protected void error(String message) {
                DiscoLocator.this.log(message);
            }
        };

        List<Disco.Package> jdks = disco.getPackages(version, OS.CURRENT, Distro.TEMURIN, Arch.CURRENT);
        if (jdks == null || jdks.isEmpty()) {
            log("Failed to find any distros from Disco for " + version + " " + OS.CURRENT + " " + Arch.CURRENT + " " + Distro.TEMURIN);

            // Try any vendor
            jdks = disco.getPackages(version, OS.CURRENT, null, Arch.CURRENT);
            if (jdks == null || jdks.isEmpty()) {
                log("Failed to find any distros from Disco for " + version + " " + OS.CURRENT + " " + Arch.CURRENT);

                // Try any Architecture and just hope for the best
                jdks = disco.getPackages(version, OS.CURRENT, null, null);
                if (jdks == null || jdks.isEmpty()) {
                    log("Failed to find any distros from Disco for " + version + " " + OS.CURRENT);
                    return null;
                }
            }
        }

        log("Found " + jdks.size() + " download canidates");
        Disco.Package pkg = jdks.get(0);
        log("Selected " + pkg.distribution + ": " + pkg.filename);

        File java_home = disco.extract(pkg);

        if (java_home == null)
            return null;

        IJavaInstall result = fromPath(java_home);
        return result;
    }
}
