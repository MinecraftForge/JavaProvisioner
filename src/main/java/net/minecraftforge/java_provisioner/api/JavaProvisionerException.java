/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.java_provisioner.api;

import java.util.Collections;
import java.util.List;

public class JavaProvisionerException extends Exception {
    private final List<String> logOutput;

    public JavaProvisionerException(String message) {
        this(message, Collections.emptyList());
    }

    public JavaProvisionerException(String message, List<String> logOutput) {
        super(message);
        this.logOutput = logOutput;
    }

    public JavaProvisionerException(String message, Throwable cause) {
        this(message, cause, Collections.emptyList());
    }

    public JavaProvisionerException(String message, Throwable cause, List<String> logOutput) {
        super(message, cause);
        this.logOutput = logOutput;
    }

    public List<String> logOutput() {
        return logOutput;
    }
}
