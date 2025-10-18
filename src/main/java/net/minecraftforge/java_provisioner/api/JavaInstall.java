/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.java_provisioner.api;

import java.io.File;

import net.minecraftforge.java_provisioner.JavaVersion;
import org.jetbrains.annotations.Nullable;

public interface JavaInstall extends Comparable<JavaInstall> {
    File home();
    boolean isJdk();
    int majorVersion();
    @Nullable String version();
    @Nullable String vendor();

    default int compareTo(JavaInstall o2) {
        if (this.isJdk() != o2.isJdk())
            return this.isJdk() ? -1 : 1;
        if (this.majorVersion() != o2.majorVersion())
            return o2.majorVersion() - this.majorVersion();

        String vendor = this.vendor();
        String oVendor = o2.vendor();
        if (vendor != null && oVendor == null)
            return -1;
        else if (vendor == null && oVendor != null)
            return 1;
        else if (vendor != null && !vendor.equals(oVendor)) {
            int v1 = Util.getVendorOrder(vendor);
            int v2 = Util.getVendorOrder(oVendor);
            if (v1 == v2) {
                if (v1 == -1)
                    return vendor.compareTo(oVendor);
            } else if (v1 == -1)
                return 1;
            else if (v2 == -1)
                return -1;
            else
                return v1 - v2;
        }

        String version = this.version();
        String oVersion = o2.version();
        if (version != null && oVersion == null)
            return -1;
        else if (version == null && oVersion != null)
            return 1;
        else if (version != null && !version.equals(oVersion)) {
            JavaVersion v1 = JavaVersion.nullableParse(version);
            JavaVersion v2 = JavaVersion.nullableParse(oVersion);
            if (v1 == null && v2 != null)
                return 1;
            else if (v1 != null && v2 == null)
                return -1;
            else if (v1 == null)
                return version.compareTo(oVersion);
            return v2.compareTo(v1);
        }

        return 0;
    }
}
