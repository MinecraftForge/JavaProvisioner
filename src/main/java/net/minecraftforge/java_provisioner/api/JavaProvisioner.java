/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.java_provisioner.api;

import org.jetbrains.annotations.Nullable;

public interface JavaProvisioner extends JavaLocator {
    /**
     * Instructs the provider to do anything it needs to provide a compatible java installer.
     * This is currently only implemented by the Disco provider to download and extract a JDK from
     * the foojay api.
     *
     * @return Null if this failed to provision a JDK
     */
    default JavaInstall provision(int version) throws JavaProvisionerException {
        return provision(version, null);
    }

    JavaInstall provision(int version, @Nullable Distro distro) throws JavaProvisionerException;
}
