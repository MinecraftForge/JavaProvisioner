/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.java_provisioner.api;

import java.io.File;
import java.util.List;

import net.minecraftforge.java_provisioner.Disco;

public interface JavaLocator {
    /**
     * Locates the first available java install for the specified major java version.
     * @return null is no install found, or a File pointing at the JAVA_HOME directory
     */
    File find(int version) throws JavaProvisionerException;

    /**
     * Locates all possible java installations that this provider knows about.
     * This can be used as a bulk version of {@link #find(int)}
     * @return A list containing all java installs, possibly empty but not null.
     */
    default List<JavaInstall> findAll() {
        return findAll(-1);
    }

    /**
     * Locates all possible java installations that this provider knows about.
     * This can be used as a bulk version of {@link #find(int)}
     * @return A list containing all java installs, possibly empty but not null.
     */
    List<JavaInstall> findAll(int version);

    /**
     * Returns all loged messages this provider has output, honestly this is just a hack
     * until I decide what type of logging API I want to expose.
     */
    List<String> logOutput();

    /**
     * Returns a locator that attempts to find any toolchains installed by Gradle's toolchain plugin.
     * Uses GRADLE_HOME as the root directory.
     */
    static JavaLocator gradle() {
        return Disco.Locators.gradle();
    }

    /**
     * Returns a locator that search the JAVA_HOME environment variables, such as the ones provided by
     * Github Actions.
     */
    static JavaLocator home() {
        return Disco.Locators.home();
    }

    /**
     * Returns a locator that searches a set of default 'guessed' directories based on OS.
     */
    static JavaLocator paths() {
        return Disco.Locators.paths();
    }

    /**
     * Returns a locator that searches the specified directories, and immediate sub-directories.
     */
    static JavaLocator paths(File... dirs) {
        return Disco.Locators.paths(dirs);
    }

    /**
     * Returns a new locator that implements the Disco API, using the specified directory as its download cache.
     */
    static JavaProvisioner disco(File cache) {
        return Disco.Locators.disco(cache);
    }

    static JavaProvisioner disco(File cache, boolean offline) {
        return Disco.Locators.disco(cache, offline);
    }
}
