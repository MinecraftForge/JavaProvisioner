/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.java_provisioner;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.internal.bind.TypeAdapters;
import com.google.gson.stream.JsonWriter;

import net.minecraftforge.java_provisioner.util.OS;
import net.minecraftforge.java_provisioner.util.ProcessUtils;
import net.minecraftforge.util.download.DownloadUtils;
import net.minecraftforge.util.hash.HashFunction;
import net.minecraftforge.util.logging.Log;
import org.kamranzafar.jtar.TarEntry;
import org.kamranzafar.jtar.TarInputStream;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;

/**
 * A client for the <a href="https://github.com/foojayio/discoapi">foojay disco API</a>
 * <p>
 * Folder format is fairly straight forward, the package filename is the archive name from
 * 'package info' the api returns. I could use the package ID to get unique names, however
 * I thought using the filename was unique enough and provided more human readable names.
 * <p>
 *
 * TODO: [DISCO][Threads] Locking files for multiple processes accessing the same cache directory
 */
public class Disco {
    private static final int CACHE_TIMEOUT = 1000 * 60 * 60 * 12; // 12 hours

    // A GSO parser that prints good looking output, and treats empty strings as nulls
    private static final Gson GSON = new GsonBuilder()
        .setLenient()
        .setPrettyPrinting()
        .registerTypeAdapter(String.class, new TypeAdapter<String>() {
            @Override
            public void write(final JsonWriter out, final String value) throws IOException {
                if (value == null || value.isEmpty())
                    out.nullValue();
                else
                    TypeAdapters.STRING.write(out, value);
            }

            @Override
            public String read(final JsonReader in) throws IOException {
                String value = TypeAdapters.STRING.read(in);
                return value != null && value.isEmpty() ? null : value; // Read empty strings as null
            }
        })
        .create();

    private final File cache;
    private final String provider;
    private final boolean offline;

    public Disco(File cache) {
        this(cache, "https://api.foojay.io/disco/v3.0");
    }

    public Disco(File cache, boolean offline) {
        this(cache, "https://api.foojay.io/disco/v3.0", offline);
    }

    public Disco(File cache, String provider) {
        this(cache, provider, false);
    }

    public Disco(File cache, String provider, boolean offline) {
        this.cache = cache;
        this.provider = provider;
        this.offline = offline;
    }

    protected void debug(String message) {
        Log.debug(message);
    }

    protected void error(String message) {
        Log.error(message);
    }

    public List<Package> getPackages() {
        File tmp = new File(cache, "packages.json");
        List<Package> ret = readJson(tmp, new TypeToken<List<Package>>(){});
        if (ret != null)
            return ret;

        if (offline)
            return null;

        String url = provider + "/packages/?"
            + "&package_type=jdk" // JDK has everything, could pull just the JRE but who cares.
            + "&directly_downloadable=true" // This doesn't actually seem to do anything but it's in the spec...
            + "&archive_type=zip,tar,tar.gz,tgz" // Formats that we support
        ;

        debug("Downloading package list");
        String data = DownloadUtils.tryDownloadString(true, url);
        if (data == null)
            return null;

        Response<Package> resp = new Response<>(data, Package.class);

        if (resp.entries().isEmpty()) {
            error("Failed to download any packages from " + url);
            return null;
        }

        writeJson(tmp, resp.entries(), List.class);

        return resp.entries();
    }

    public List<Package> getPackages(int version) {
        return getPackages(version, OS.CURRENT, Distro.TEMURIN, Arch.CURRENT);
    }

    public List<Package> getPackages(int version, OS os, Distro distro, Arch arch) {
        List<Package> jdks = getPackages();
        if (jdks == null)
            return null;

        int max_jdk_version = -1;

        List<Package> ret = new ArrayList<>();
        for (Package pkg : jdks) {
            if (version != -1 && pkg.jdk_version != version)
                continue;
            if (os != null && pkg.os() != os)
                continue;
            if (distro != null && pkg.distro() != distro)
                continue;
            if (arch != null) {
                Arch parch = pkg.arch();
                if (arch != parch && (arch.parent == null || arch.parent != parch))
                    continue;
            }
            if (LibC.CURRENT != LibC.MUSL && pkg.libC() == LibC.MUSL)
                continue; // TODO: [DISCO][Hack] Find a good way to detect actual libc type, this is not great

            if (max_jdk_version < pkg.jdk_version)
                max_jdk_version = pkg.jdk_version;

            ret.add(pkg);
        }

        if (version == -1) {
            for (Iterator<Package> itr = ret.iterator(); itr.hasNext();) {
                Package pkg = itr.next();
                if (pkg.jdk_version < max_jdk_version)
                    itr.remove();
            }
        }

        Collections.sort(ret);

        return ret;
    }

    public PackageInfo getInfo(Package pkg) {
        File tmp = new File(cache, pkg.filename + ".json");
        DownloadInfo ret = readJson(tmp, TypeToken.get(DownloadInfo.class));
        if (ret != null && ret.info != null)
            return ret.info;

        if (offline)
            return null;

        //debug("Downloading package info " + pkg.id);
        String url = provider + "/ids/" + pkg.id;
        String data = DownloadUtils.tryDownloadString(true, url);
        if (data == null)
            return null;

        Response<PackageInfo> resp = new Response<>(data, PackageInfo.class);

        if (resp.entries().isEmpty()) {
            error("Failed to download package info for "+ pkg.id);
            return null;
        } else if (resp.entries().size() != 1) { // This never happens, but output a warning if it does.
            debug("Warning: Multiple package infos returned from " + url);
        }

        ret = new DownloadInfo(pkg, resp.entries().get(0));
        writeJson(tmp, ret, DownloadInfo.class);
        return ret.info;
    }

    public File download(Package pkg) {
        PackageInfo info = getInfo(pkg);

        Map<HashFunction, String> checksums = new EnumMap<>(HashFunction.class);
        String download = pkg.links.pkg_download_redirect;

        //debug("Downloading " + pkg.filename);
        if (info == null) {
            debug("Failed to download package info for \"" + pkg.filename + "\" (" + pkg.id + ") , assuming redirect link is valid");
        } else {
            if (info.checksum != null && info.checksum_type != null) {
                HashFunction func = HashFunction.find(info.checksum_type);
                if (func != null)
                    checksums.put(func, info.checksum);
                else
                    debug("Unknown Checksum " + info.checksum_type + ": " + info.checksum);
            } else if (info.checksum_uri != null && !offline) {
                String raw = DownloadUtils.tryDownloadString(true, info.checksum_uri);
                if (raw != null) {
                    String checksum = raw.split(" ")[0];
                    HashFunction func = HashFunction.findByHash(checksum);
                    if (func != null)
                        checksums.put(func, checksum);
                    else
                        debug("Unknown Checksum " + checksum);
                }
            }

            if (info.direct_download_uri != null)
                download = info.direct_download_uri;
        }

        File archive = new File(cache, pkg.filename);
        if (!archive.exists()) {
            if (download == null) {
                if (offline)
                    error("Offline mode, can't download " + pkg.filename + " (" + pkg.id + ")");
                else
                    error("Failed to find download link for " + pkg.filename + " (" + pkg.id + ")");
                return null;
            }
            debug("Downloading " + download);
            if (!DownloadUtils.tryDownloadFile(true, archive, download)) {
                error("Failed to download " + pkg.filename + " from " + download);
                return null;
            }
        }

        if (!checksums.isEmpty()) {
            debug("Verifying checksums");
            boolean success = true;
            for (HashFunction func : checksums.keySet()) {
                try {
                    String actual = func.hash(archive);
                    String expected = checksums.get(func);
                    if (expected.equals(actual)) {
                        debug("    " + func.name() + " Validated");
                    } else {
                        success = false;
                        debug("    " + func.name() + " Invalid");
                        debug("        Expected: " + expected);
                        debug("        Actual:   " + actual);
                    }
                } catch (IOException e) {
                    error("Failed to calculate " + func.name() + " checksum: " + e.getMessage());
                    return null;
                }
            }
            if (!success)
                return null;
        } else {
            debug("    No checksum found, assuming existing file is valid");
        }

        return archive;
    }

    private File getExtractedDir(Package pkg) {
        String filename = pkg.filename;
        Archive format = pkg.archive();
        if (format == null)
            format = Archive.byFilename(filename);

        if (filename.endsWith(".tar.gz"))
            filename = filename.substring(0, filename.length() - 7);
        else {
            int idx = filename.lastIndexOf('.');
            if (idx != -1)
                filename = filename.substring(0, idx);
        }

        return new File(cache, filename);
    }

    public File extract(Package pkg) {
        File archive = new File(cache, pkg.filename);
        if (!archive.exists())
            archive = download(pkg);

        if (archive == null)
            return null;

        File extracted = getExtractedDir(pkg);
        return extract(archive, extracted, pkg.os(), pkg.archive());
    }

    private File extract(File archive, File target, OS os, Archive format) {
        String exeName = "bin/java" + OS.CURRENT.exe();
        File exe = new File(target, exeName);
        if (exe.exists())
            return target;

        debug("Extracting " + archive + " to: " + target);
        target = target.getAbsoluteFile();
        if (!target.exists())
            target.mkdirs();

        if (format == Disco.Archive.TAR || format == Disco.Archive.TGZ || format == Disco.Archive.TAR_GZ) {
            extractTar(exeName, archive, target, os, format == Disco.Archive.TGZ || format == Disco.Archive.TAR_GZ);
        } else if (format == Disco.Archive.ZIP) {
            extractZip(exeName, archive, target);
        } else {
            error("    Unknown archive format.. can't continue");
            return null;
        }

        if (!exe.exists()) {
            error("    Extracting failed to produce expected java executable: " + exe.getAbsolutePath());
            // TODO: [DISCO][Cleanup] Delete failed extraction
            return null;
        }

        return target;
    }

    private void extractZip(String exeName, File archive, File target) {
        boolean posix = Files.getFileAttributeView(target.toPath(), PosixFileAttributeView.class) != null;

        try (ZipFile zip = new ZipFile(archive)) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());

            // First pass, find the executable file, to see if we need to remove a prefix
            String prefix = null;
            for (ZipEntry entry : entries) {
                String name = entry.getName().replace('\\', '/'); // Normalize, mandrel uses \ when everything else uses /
                if (name.endsWith(exeName)) {
                    prefix = name.substring(0, name.length() - exeName.length());
                    break;
                }
            }

            if (prefix != null)
                debug("   Prefix: " + prefix);

            for (ZipEntry entry : entries) {
                int bits = getZipMode(entry.getExtra());
                InputStream stream = zip.getInputStream(entry);
                String name = entry.getName().replace('\\', '/'); // Normalize as some zips don't use /
                boolean isDir = entry.isDirectory() || name.endsWith("/");

                if (!extractFile(archive, posix, target, prefix, name, stream, bits, isDir))
                    return;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static int getZipMode(byte[] data) throws IOException {
        int bits = 0b111_101_101; // Default, let everyone read/execute but only the owner write
        if (data == null)
            return bits;

        // Find perms from extra info if possible
        // https://libzip.org/specifications/extrafld.txt
        DataInputStream bin = new DataInputStream(new ByteArrayInputStream(data));
        while (bin.available() > 0) {
            int id = bin.readUnsignedShort();
            int len = bin.readUnsignedShort();
            if (id == 0x756e) {
                /*
                 *         -ASi Unix Extra Field:
                 *          ====================
                 *
                 *          The following is the layout of the ASi extra block for Unix.  The
                 *          local-header and central-header versions are identical.
                 *          (Last Revision 19960916)
                 *
                 *          Value         Size        Description
                 *          -----         ----        -----------
                 *  (Unix3) 0x756e        Short       tag for this extra block type ("nu")
                 *          TSize         Short       total data size for this block
                 *          CRC           Long        CRC-32 of the remaining data
                 *          Mode          Short       file permissions
                 *          SizDev        Long        symlink'd size OR major/minor dev num
                 *          UID           Short       user ID
                 *          GID           Short       group ID
                 *          (var.)        variable    symbolic link filename
                 *
                 *          Mode is the standard Unix st_mode field from struct stat, containing
                 *          user/group/other permissions, setuid/setgid and symlink info, etc.
                 *
                 *          If Mode indicates that this file is a symbolic link, SizDev is the
                 *          size of the file to which the link points.  Otherwise, if the file
                 *          is a device, SizDev contains the standard Unix st_rdev field from
                 *          struct stat (includes the major and minor numbers of the device).
                 *          SizDev is undefined in other cases.
                 *
                 *          If Mode indicates that the file is a symbolic link, the final field
                 *          will be the name of the file to which the link points.  The file-
                 *          name length can be inferred from TSize.
                 *
                 *          [Note that TSize may incorrectly refer to the data size not counting
                 *           the CRC; i.e., it may be four bytes too small.]
                 */
                bin.skip(4); // CRC-32
                bits = bin.readUnsignedShort() & 0b111_111_111; // I only care about the file perms
                bin.skip(len - 6);
            } else {
                bin.skip(len);
            }
        }

        return bits;
    }

    private boolean extractFile(File archive, boolean posix, File target, String prefix, String name, InputStream stream, int bits, boolean isDir) throws IOException {
        if (isDir)
            return true;

        if (prefix != null) {
            if (!name.startsWith(prefix))
                return true;
            name = name.substring(prefix.length());
        }

        File out = new File(target, name).getAbsoluteFile();
        //log("    Extracting: " + name);
        if (!out.getAbsolutePath().startsWith(target.getAbsolutePath())) {
            error("Failed to extract " + archive);
            error("    Invalid file! " + name);
            error("      Would not be extracted to target directory, could be malicious archive! Exiting");
            return false;
        }

        File parent = out.getParentFile();
        if (!parent.exists())
            parent.mkdirs();

        Files.copy(stream, out.toPath(), StandardCopyOption.REPLACE_EXISTING);

        if (posix) {
            Set<PosixFilePermission> perms = new HashSet<>();
            int mask = 0b100_000_000;
            for (PosixFilePermission perm : PosixFilePermission.values()) {
                if ((bits & mask) != 0)
                    perms.add(perm);
                mask >>= 1;
            }
            Files.setPosixFilePermissions(out.toPath(), perms);
        }

        return true;
    }

    private static InputStream getFileStream(File file, boolean gziped) throws IOException {
        InputStream stream = new FileInputStream(file);
        if (gziped)
            stream = new GZIPInputStream(stream);
        return stream;
    }

    private void extractTar(String exeName, File archive, File target, OS os, boolean gziped) {
        // First pass, find the executable file, to see if we need to remove a prefix
        String prefix = null;
        try (TarInputStream tar = new TarInputStream(getFileStream(archive, gziped))) {
            for (TarEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                String name = entry.getName();
                if (name.endsWith(exeName)) {
                    prefix = name.substring(0, name.length() - exeName.length());
                    break;
                }
            }
        } catch (IOException e) {
            error("    Failed to read tar file: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        if (prefix != null)
            debug("    Prefix: " + prefix);

        boolean posix = Files.getFileAttributeView(target.toPath(), PosixFileAttributeView.class) != null;
        try (TarInputStream tar = new TarInputStream(getFileStream(archive, gziped))) {
            for (TarEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                int bits = entry.getHeader().mode;
                boolean isDir = entry.isDirectory();
                String name = entry.getName();
                if (!extractFile(archive, posix, target, prefix, name, tar, bits, isDir))
                    return;
            }
        } catch (IOException e) {
            error("    Failed to extract: " + e.getMessage());
            e.printStackTrace();
            return;
        }
    }


    private <T> T readJson(File input, TypeToken<T> type) {
        if (!input.exists() || input.lastModified() < System.currentTimeMillis() - CACHE_TIMEOUT)
            return null;

        try (FileReader reader = new FileReader(input)) {
            return GSON.fromJson(new JsonReader(reader), type);
        } catch (IOException e) {
            debug("Can not read cache file: " + e.getMessage());
        }
        return null;
    }

    private static <T> void writeJson(File output, T data, Class<T> type) {
        output.getParentFile().mkdirs();
        try (BufferedWriter out = Files.newBufferedWriter(output.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            GSON.toJson(data, type, GSON.newJsonWriter(out));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public enum Arch {
        X86("x86", "x86", "x32", "286"),
        X64,
        AARCH32,
        AARCH64,
        PPC,
        PPC64,

        AMD64(X64, "amd64", "amd64", "_amd64"),
        ARM,
        ARM32("arm32", "arm32", "aarch32", "armv6", "armv7l", "armv7"),
        ARM64("arm64", "arm64", "armv8"),
        MIPS,
        PPC64EL(PPC64),
        PPC64LE(PPC64),
        RISCV64(AARCH64, "riscv64", "riscv64", "risc-v", "riscv"),
        S390,
        S390X,
        SPARC,
        SPARC_V9("sparcv9"),
        X86_64(X64, "x86-64", "x86-64", "x86_64", "x86lx64"),
        X86_32(X86, "x86-32", "x86-32", "x86_32", "x86lx32"),
        I386(X86, "i386", "i396", "386"),
        I486(X86, "i486", "i496", "486"),
        I586(X86, "i586", "i596", "586"),
        I686(X86, "i686", "i696", "686"),
        UNKNOWN;

        private static final Arch[] $values = values();
        public static final Arch CURRENT = getCurrent();

        private final Arch parent;
        private final String key;
        private final String[] names;

        private Arch() {
            this(null);
        }

        private Arch(Arch parent) {
            this.parent = parent;
            this.key = name().toLowerCase(Locale.ENGLISH);
            this.names = new String[] { this.key };
        }

        private Arch(String key, String... names) {
            this(null, key, names);
        }

        private Arch(Arch parent, String key, String... names) {
            this.parent = parent;
            this.key = key;
            this.names = names;
        }

        public boolean is64Bit() {
            return this == X64 || this == AMD64 || this == ARM64 || this == X86_64 || this == AARCH64 || this == PPC64 || this == PPC64EL || this == RISCV64;
        }

        public String key() {
            return this.key;
        }

        public static Arch byKey(String key) {
            for (Arch value : $values) {
                if (value.key.equals(key))
                    return value;
            }
            return null;
        }

        private static Arch getCurrent() {
            String prop = System.getProperty("os.arch").toLowerCase(Locale.ENGLISH);
            for (Arch value : $values) {
                for (String name : value.names) {
                    if (prop.equals(name)) {
                        return value;
                    }
                }
            }
            return UNKNOWN;
        }
    }

    public enum Distro implements Comparable<Distro> {
        // These are well know/recommended distros
        TEMURIN,
        AOJ,
        ZULU,

        // Everything else
        AOJ_OPENJ9,
        BISHENG,
        CORRETTO,
        DRAGONWELL,
        GRAALVM_CE8,
        GRAALVM_CE11,
        GRAALVM_CE16,
        GRAALVM_CE17,
        GRAALVM_CE19,
        GRAALVM_CE20,
        GRAALVM_COMMUNITY,
        GRAALVM,
        JETBRAINS,
        KONA,
        LIBERICA,
        LIBERICA_NATIVE,
        MANDREL,
        MICROSOFT,
        OJDK_BUILD,
        OPENLOGIC,
        ORACLE,
        ORACLE_OPEN_JDK,
        REDHAT,
        SAP_MACHINE,
        SEMERU,
        SEMERU_CERTIFIED,
        TRAVA,
        ZULU_PRIME;

        private static final Distro[] $values = values();
        private final String key;

        private Distro() {
            this.key = this.name().toLowerCase(Locale.ENGLISH);
        }

        public String key() {
            return this.key;
        }

        public static Distro byKey(String key) {
            for (Distro value : $values) {
                if (value.key.equals(key))
                    return value;
            }
            return null;
        }
    }

    public enum Archive {
        APK,
        CAB,
        DEB,
        DMG,
        EXE,
        MSI,
        PKG,
        RPM,
        TAR,
        TAR_GZ("tar.gz"),
        TGZ,
        ZIP;

        private static final Archive[] $values = values();
        private final String key;

        private Archive() {
            this.key = this.name().toLowerCase(Locale.ENGLISH);
        }

        private Archive(String key) {
            this.key = key;
        }

        public String key() {
            return this.key;
        }

        public static Archive byFilename(String name) {
            name = name.toLowerCase(Locale.ENGLISH);
            for (Archive value : $values) {
                if (name.endsWith("." + value.key()))
                    return value;
            }
            return null;
        }

        public static Archive byKey(String key) {
            for (Archive value : $values) {
                if (value.key.equals(key))
                    return value;
            }
            return null;
        }
    }

    public enum LibC {
        GLIBC,
        LIBC,
        MUSL,
        C_STD_LIB;

        private static final LibC[] $values = values();
        private final String key;

        private LibC() {
            this.key = this.name().toLowerCase(Locale.ENGLISH);
        }

        public static LibC byKey(String key) {
            for (LibC value : $values) {
                if (value.key.equals(key))
                    return value;
            }
            return null;
        }
        public static final LibC CURRENT = getCurrent();

        private static final LibC getCurrent() {
            if (OS.CURRENT == OS.MUSL)
                return MUSL;
            if (OS.CURRENT != OS.LINUX && OS.CURRENT != OS.ALPINE)
                return GLIBC;

            ProcessUtils.Result getconf = ProcessUtils.runCommand("getconf", "GNU_LIBC_VERSION");
            if (getconf.exitCode == 0) {
                for (String line : getconf.lines) {
                    if (line.toLowerCase(Locale.ENGLISH).contains("musl"))
                        return MUSL;
                }
            } else {
                Log.error("Failed to run `getconf GNU_LIBC_VERSION`: " + getconf.lines.get(0));
            }

            ProcessUtils.Result ldd = ProcessUtils.runCommand("ldd", "--version");
            if (ldd.exitCode == 0) {
                for (String line : ldd.lines) {
                    if (line.toLowerCase(Locale.ENGLISH).contains("musl"))
                        return MUSL;
                }
            } else {
                Log.error("Failed to run `ldd --version`: " + ldd.lines.get(0));
            }
            return GLIBC;
        }
    }

    @SuppressWarnings("unused")
    private static class Response<T> {
        public final String raw;
        private final List<T> entries;
        public final String message;

        Response(String raw, final Class<T> clazz) {
            this(raw, e -> GSON.fromJson(e, clazz));
        }

        Response(String raw, Parser<T> converter) {
            this.raw = raw;

            String message = null;
            List<T> entries = null;

            try {
                JsonObject root = GSON.fromJson(raw, JsonObject.class);
                if (root.has("message"))
                    message = root.get("message").getAsString();

                JsonArray result = root.getAsJsonArray("result");
                if (result != null) {
                    List<T> tmp = new ArrayList<>();
                    for (JsonElement entry : result) {
                        tmp.add(converter.apply(entry));
                    }
                    entries = Collections.unmodifiableList(tmp);
                }
            } catch (JsonSyntaxException e) {
                e.printStackTrace();
            }

            this.message = message;
            this.entries = entries;
        }

        public List<T> entries() {
            return this.entries == null ? Collections.<T>emptyList() : this.entries;
        }

        public static interface Parser<T> {
            T apply(JsonElement e) throws JsonSyntaxException;
        }
    }

    /*
     *{
     *   "id": "b59bd89cc54927a1609bb71af0d8921a",
     *   "archive_type": "zip",
     *   "distribution": "zulu",
     *   "major_version": 22,
     *   "java_version": "22.0.2+9",
     *   "distribution_version": "22.32.15",
     *   "jdk_version": 22,
     *   "latest_build_available": true,
     *   "release_status": "ga",
     *   "term_of_support": "sts",
     *   "operating_system": "linux",
     *   "lib_c_type": "glibc",
     *   "architecture": "x64",
     *   "fpu": "unknown",
     *   "package_type": "jdk",
     *   "javafx_bundled": false,
     *   "directly_downloadable": true,
     *   "filename": "zulu22.32.15-ca-jdk22.0.2-linux_x64.zip",
     *   "links": {
     *       "pkg_info_uri": "https://api.foojay.io/disco/v3.0/ids/b59bd89cc54927a1609bb71af0d8921a",
     *       "pkg_download_redirect": "https://api.foojay.io/disco/v3.0/ids/b59bd89cc54927a1609bb71af0d8921a/redirect"
     *   },
     *   "free_use_in_production": true,
     *   "tck_tested": "yes",
     *   "tck_cert_uri": "https://cdn.azul.com/zulu/pdf/cert.zulu22.32.15-ca-jdk22.0.2-linux_x64.zip.pdf",
     *   "aqavit_certified": "unknown",
     *   "aqavit_cert_uri": "",
     *   "size": 215197508,
     *   "feature": []
     *}
     */
    public static class Package implements Comparable<Package> {
        public String id;
        public int major_version;
        public int jdk_version;
        public boolean javafx_bundled;
        public String filename;
        public Links links;
        public int size;

        public String java_version;
        private transient JavaVersion javaVersion;
        public JavaVersion javaVersion() {
            if (javaVersion == null && java_version != null)
                javaVersion = JavaVersion.parse(java_version);
            return javaVersion;
        }

        public String archive_type;
        private transient Archive archive;
        public Archive archive() {
            if (archive == null && archive_type != null) {
                archive = Archive.byKey(archive_type);
                if (archive == null && filename != null)
                    archive = Archive.byFilename(filename);
            }
            return archive;
        }

        public String distribution;
        private transient Distro distro;
        public Distro distro() {
            if (distro == null && distribution != null)
                distro = Distro.byKey(distribution);
            return distro;
        }

        public String operating_system;
        private transient OS os;
        public OS os() {
            if (os == null && operating_system != null)
                os = OS.byKey(operating_system);
            return os;
        }

        public String lib_c_type;
        private transient LibC libC;
        public LibC libC() {
            if (libC == null && lib_c_type != null)
                libC = LibC.byKey(lib_c_type);
            return libC;
        }

        public String architecture;
        private transient Arch arch;
        public Arch arch() {
            if (arch == null && architecture != null)
                arch = Arch.byKey(architecture);
            return arch;
        }

        public static final class Links {
            public String pkg_info_uri;
            public String pkg_download_redirect;
        }

        @Override
        public int compareTo(Package o) {
            if (o == null)
                return -1;
            if (jdk_version != o.jdk_version)
                return o.jdk_version - jdk_version;
            if (compare(distro(), o.distro()) != 0)
                return compare(distro(), o.distro());
            if (compare(libC(), o.libC()) != 0)
                return compare(libC(), o.libC());

            if (javaVersion() != null)
                return javaVersion().compareTo(o.javaVersion());
            else if (o.javaVersion() != null)
                return -1;

            return 0;
        }

        private <T extends Enum<T>> int compare(T e1, T e2) {
            if (e1 == null && e2 != null)
                return 1;
            if (e1 != null && e2 == null)
                return -1;
            if (e1 == e2)
                return 0;
            return e1.compareTo(e2);
        }
    }

    /*
     *{
     *    "filename": "OpenJDK22U-jdk_x64_windows_hotspot_22.0.1_8.zip",
     *    "direct_download_uri": "https://github.com/adoptium/temurin22-binaries/releases/download/jdk-22.0.1%2B8/OpenJDK22U-jdk_x64_windows_hotspot_22.0.1_8.zip",
     *    "download_site_uri": "",
     *    "signature_uri": "https://github.com/adoptium/temurin22-binaries/releases/download/jdk-22.0.1%2B8/OpenJDK22U-jdk_x64_windows_hotspot_22.0.1_8.zip.sig",
     *    "checksum_uri": "https://github.com/adoptium/temurin22-binaries/releases/download/jdk-22.0.1%2B8/OpenJDK22U-jdk_x64_windows_hotspot_22.0.1_8.zip.sha256.txt",
     *    "checksum": "4cf9d3c7ed8ec72a8adcca290d90fdd775100a38670410e674b05233a0c8288e",
     *    "checksum_type": "sha256"
     *}
     */
    public static class PackageInfo {
        public final String filename = null;
        public final String direct_download_uri = null;
        public final String download_side_uri = null;
        public final String signature_uri = null;
        public final String checksum_uri = null;
        public final String checksum = null;
        public final String checksum_type = null;
    }

    // Just a helper class that wraps all the information we know about a file, saved in the cache for easy reference
    private static class DownloadInfo {
        @SuppressWarnings("unused")
        private Package pkg;
        private PackageInfo info;

        private DownloadInfo(Package pkg, PackageInfo info) {
            this.pkg = pkg;
            this.info = info;
        }
    }
}
