/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.java_provisioner;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import net.minecraftforge.java_provisioner.api.JavaInstall;
import net.minecraftforge.java_provisioner.api.JavaProvisionerException;
import net.minecraftforge.util.os.OS;
import org.jetbrains.annotations.Nullable;

/*
 * Attempts to find the java install using various tools that Gradle uses
 *
 * https://docs.gradle.org/current/userguide/toolchains.html#sec:custom_loc
 *   System Properties:
 *     org.gradle.java.installations.fromEnv=ENV1,ENV2
 *     org.gradle.java.installations.paths=PATH1,PATH2
 *
 * https://docs.gradle.com/enterprise/test-distribution-agent/#capabilities
 *   Environment Variables: JDK\d\d*
 *
 * GRADLE_HOME/jdks folder
 */
final class GradleLocator extends JavaHomeLocator {
    private static final String GRADLE_FROMENV = "org.gradle.java.installations.fromEnv";
    private static final String GRADLE_PATHS = "org.gradle.java.installations.paths";
    private static final String MARKER_FILE = ".ready";
    private static final String LEGACY_MARKER_FILE = "provisioned.ok";
    private static final String MAC_JAVA_HOME_FOLDER = "Contents/Home";
    private static final Pattern GRADLE_ENV = Pattern.compile("JDK\\d\\d*");

    @Override
    public File find(int version) throws JavaProvisionerException {
        JavaInstall ret = fromGradleEnv(null, version);
        if (ret != null)
            return ret.home();

        ret = fromPaths(null, version);
        if (ret != null)
            return ret.home();

        for (String key : System.getenv().keySet()) {
            if (GRADLE_ENV.matcher(key).matches()) {
                ret = fromEnv(key, version);
                if (ret != null)
                    return ret.home();
            }
        }

        ret = fromGradleHome(null, version);
        if (ret != null)
            return ret.home();

        throw new JavaProvisionerException("Failed to find any Java installations from Gradle paths and properties");
    }

    @Override
    public List<JavaInstall> findAll(int version) {
        List<JavaInstall> ret = new ArrayList<>();

        fromGradleEnv(ret, version);
        fromPaths(ret, version);

        for (String key : System.getenv().keySet()) {
            if (GRADLE_ENV.matcher(key).matches()) {
                JavaInstall install = fromEnv(key, version);
                if (install != null)
                    ret.add(install);
            }
        }

        fromGradleHome(ret, version);

        if (ret.isEmpty())
            log("Failed to find any Java installations from Gradle paths and properties");

        return ret;
    }

    private @Nullable JavaInstall fromGradleEnv(@Nullable List<JavaInstall> results, int version) {
        String prop = System.getProperty(GRADLE_FROMENV);
        log("Property: " + GRADLE_FROMENV + " = " + prop);
        if (prop == null) {
            log("Could not find " + GRADLE_FROMENV + " in system properties");
            return null;
        }

        String[] envs = prop.split(",");
        List<JavaInstall> found = new ArrayList<>(envs.length);
        for (String env : envs) {
            JavaInstall install = fromEnv(env, version);
            if (install != null) {
                if (results == null)
                    return install;
                else
                    found.add(install);
            }
        }

        if (found.isEmpty()) {
            log("Could not find any of " + prop + " in " + GRADLE_FROMENV);
            return null;
        }

        results.addAll(found);
        return null;
    }

    private @Nullable JavaInstall fromPaths(@Nullable List<JavaInstall> results, int version) {
        String prop = System.getProperty(GRADLE_PATHS);
        log("Property: " + GRADLE_PATHS + " = " + prop);
        if (prop == null) {
            log("Could not find " + GRADLE_PATHS + " in system properties");
            return null;
        }

        String[] envs = prop.split(",");
        List<JavaInstall> found = new ArrayList<>(envs.length);
        for (String path : envs) {
            JavaInstall install = fromPath(path, version);
            if (install != null) {
                if (results == null)
                    return install;
                else
                    found.add(install);
            }
        }

        if (found.isEmpty()) {
            log("Could not find any of " + prop + " in " + GRADLE_PATHS);
            return null;
        }

        results.addAll(found);
        return null;
    }

    private File getGradleHome() {
        String home = System.getProperty("gradle.user.home");
        if (home == null) {
            home = System.getenv("GRADLE_USER_HOME");
            if (home == null)
                home = System.getProperty("user.home") + "/.gradle";
        }

        File ret = new File(home);
        try {
            return ret.getCanonicalFile();
        } catch (IOException e) {
            log("Could not get canonical path for Gradle home: " + ret);
            return ret.getAbsoluteFile();
        }
    }

    private @Nullable JavaInstall fromGradleHome(@Nullable List<JavaInstall> results, int version) {
        File gradleHome = getGradleHome();
        if (!gradleHome.exists() || !gradleHome.isDirectory()) {
            log("Gradle home: \"" + gradleHome.getAbsolutePath() + "\" Does not exist");
            return null;
        }
        File jdks = new File(gradleHome, "jdks");
        if (!jdks.exists() || !jdks.isDirectory()) {
            log("Gradle Home JDKs: \"" + jdks.getAbsolutePath() + "\" Does not exist");
            return null;
        }

        File[] listFiles = jdks.listFiles();
        if (listFiles == null) {
            log("Gradle Home JDKs: \"" + jdks.getAbsolutePath() + "\" Cannot be queried");
            return null;
        }

        List<@Nullable JavaInstall> found = new ArrayList<>(listFiles.length);
        for (File dir : listFiles) {
            if (!dir.isDirectory())
                continue;

            List<File> markers = findMarkers(dir);

            for (File marked : markers) {
                if (OS.current() == OS.MACOS) {
                    marked = findMacHome(dir);
                }

                log("Gradle Home JDK: \"" + marked.getAbsolutePath() + "\"");

                JavaInstall install = fromPath(marked, version);
                if (install != null) {
                    if (results == null)
                        return install;
                    else
                        found.add(install);
                }
            }
        }

        if (found.isEmpty()) {
            log("Could not find any Java installations in " + jdks.getAbsolutePath());
            return null;
        }

        results.addAll(found);
        return null;
    }

    private List<File> findMarkers(File root) {
        // Prior to Gradle 8.8 jdks did not have their root directory trimmed
        // It also could cause multiple archives to be extracted to the same folder.
        // So lets just find anything that it marked as 'properly installed' and hope for the best.
        List<File> ret = new ArrayList<>();
        if (new File(root, MARKER_FILE).exists() ||
            new File(root, LEGACY_MARKER_FILE).exists())
            ret.add(root);

        File[] listFiles = root.listFiles();
        if (listFiles == null) {
            log("An unexpected error occured trying to find markers in: " + root);
            return ret;
        }

        for (File child : listFiles) {
            if (!child.isDirectory())
                continue;
            if (new File(child, MARKER_FILE).exists() ||
                new File(child, LEGACY_MARKER_FILE).exists())
                ret.add(child);
        }

        return ret;
    }

    // Macs are weird and can have their files packaged into a content folder
    private File findMacHome(File root) {
        File tmp = new File(root, MAC_JAVA_HOME_FOLDER);
        if (tmp.exists())
            return tmp;

        File[] listFiles = root.listFiles();
        if (listFiles == null) {
            log("An unexpected error occurred trying to find the macOS home of: " + root);
            return root;
        }

        for (File child : listFiles) {
            if (!child.isDirectory())
                continue;

            tmp = new File(child, MAC_JAVA_HOME_FOLDER);
            if (tmp.exists())
                return tmp;
        }

        return root;
    }
}
