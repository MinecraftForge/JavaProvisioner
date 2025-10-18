/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.java_provisioner;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import net.minecraftforge.java_provisioner.api.Distro;
import net.minecraftforge.java_provisioner.api.JavaInstall;
import net.minecraftforge.java_provisioner.api.JavaProvisioner;
import net.minecraftforge.java_provisioner.api.JavaProvisionerException;
import net.minecraftforge.util.os.OS;
import org.jetbrains.annotations.Nullable;

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
final class DiscoLocator extends JavaHomeLocator implements JavaProvisioner {
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
    @SuppressWarnings("DataFlowIssue")
    public File find(int version) throws JavaProvisionerException {
        return findInternal(null, version).home();
    }

    @Override
    public List<JavaInstall> findAll() {
        try {
            List<JavaInstall> ret = new ArrayList<>();
            findInternal(ret, -1);
            return ret;
        } catch (JavaProvisionerException e) {
            log(e.getMessage());
            return Collections.emptyList();
        }
    }

    private @Nullable JavaInstall findInternal(@Nullable List<JavaInstall> results, int version) throws JavaProvisionerException {
        if (!cache.exists() || !cache.isDirectory())
            throw new JavaProvisionerException("Java Provisioner has not provisioned any Java installations", logOutput());

        List<JavaInstall> found = new ArrayList<>();

        File[] listFiles = cache.listFiles();
        if (listFiles == null)
            throw new JavaProvisionerException("Could not list contents of Disco cache: " + cache, logOutput());

        for (File dir : listFiles) {
            if (!dir.isDirectory())
                continue;

            log("Disco Cache: \"" + dir.getAbsolutePath() + "\"");

            JavaInstall install = fromPath(dir, version);
            if (install != null) {
                if (results == null)
                    return install;
                else
                    found.add(install);
            }
        }

        if (found.isEmpty())
            throw new JavaProvisionerException("Failed to find any Java installations from Disco cache");

        results.addAll(found);
        return null;
    }

    @Override
    public JavaInstall provision(int version, @Nullable Distro distro) throws JavaProvisionerException {
        Disco disco = new Disco(cache, offline) {
            @Override
            protected void debug(String message) {
                DiscoLocator.this.log(message);
            }

            @Override
            protected void error(String message) {
                DiscoLocator.this.log(message);
            }
        };

        List<Disco.Package> jdks;
        try {
            jdks = disco.getPackages(version, OS.current(), distro, Disco.Arch.CURRENT);
        } catch (Exception e) {
            log(String.format("Failed to find any JDKs from Disco for: %s%d - %s %s", distro != null ? distro + " " : "", version, OS.current(), Disco.Arch.CURRENT));

            // Try any Architecture and just hope for the best
            try {
                jdks = disco.getPackages(version, OS.current(), distro, null);
            } catch (Exception suppressed) {
                log(String.format("Failed to find any JDKs from Disco for: %s%d - %s ANY", distro != null ? distro + " " : "", version, OS.current()));
                e.addSuppressed(suppressed);
                throw new JavaProvisionerException("Failed to provision Disco download", e, logOutput());
            }
        }

        log("Found " + jdks.size() + " download candidates");
        Disco.Package pkg = jdks.get(0);
        log("Selected " + pkg.distribution + ": " + pkg.filename);

        File java_home = disco.extract(pkg);
        if (java_home == null)
            throw new JavaProvisionerException("Failed to provision Disco download: " + pkg.filename, logOutput());

        JavaInstall ret = fromPath(java_home);
        if (ret == null)
            throw new JavaProvisionerException("Failed to provision Disco download: " + pkg.filename, logOutput());

        return ret;
    }
}
