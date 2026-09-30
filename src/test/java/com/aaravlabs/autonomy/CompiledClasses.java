package com.aaravlabs.autonomy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds the compiled library classes and searches their constant pools for
 * references to forbidden types.
 *
 * <p>Two tests use this: {@link NoAndroidApiLeakTest}, which guards the
 * Android API level the library can run at, and {@link PackageBoundaryTest},
 * which guards the rule that the state framework never touches the robot.
 * Both properties are invisible to javac -- nothing stops a one-line import
 * from quietly breaking them -- so both are enforced by scanning bytecode.
 *
 * <p>A forbidden type's internal name appears as a string in the constant pool
 * of every class that names it, whether in a field descriptor, a method
 * signature, or a {@code checkcast}. That makes the constant pool a sound
 * proxy for "references this type": it cannot miss a reference, and it may
 * occasionally report one from an unrelated string literal, which is the safe
 * direction to be wrong in.
 */
final class CompiledClasses {

    private static final String ROOT = "com/aaravlabs/autonomy";

    private CompiledClasses() {
    }

    /**
     * Lists the compiled classes under {@code packagePrefix}, as paths relative to
     * that prefix.
     */
    static List<String> list(String packagePrefix) throws IOException {
        Path classes = locateCompiledClasses();
        List<String> names = new ArrayList<>();

        if (Files.isDirectory(classes)) {
            try (Stream<Path> walk = Files.walk(classes)) {
                for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".class"))::iterator) {
                    String name = classes.relativize(file).toString();
                    if (name.startsWith(packagePrefix)) {
                        names.add(name);
                    }
                }
            }
            return names;
        }

        try (ZipFile zip = new ZipFile(classes.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class") && entry.getName().startsWith(packagePrefix)) {
                    names.add(entry.getName());
                }
            }
        }
        return names;
    }

    /**
     * Returns one {@code className -> prefix} entry per forbidden prefix found in
     * the constant pool of any class under {@code packagePrefix}.
     */
    static List<String> findReferences(String packagePrefix, String[] forbiddenPrefixes)
            throws IOException {
        Path classes = locateCompiledClasses();
        List<String> hits = new ArrayList<>();

        if (Files.isDirectory(classes)) {
            try (Stream<Path> walk = Files.walk(classes)) {
                for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".class"))::iterator) {
                    String name = classes.relativize(file).toString();
                    if (name.startsWith(packagePrefix)) {
                        collect(name, readAll(file), forbiddenPrefixes, hits);
                    }
                }
            }
            return hits;
        }

        try (ZipFile zip = new ZipFile(classes.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class") || !entry.getName().startsWith(packagePrefix)) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    collect(entry.getName(), readAll(in), forbiddenPrefixes, hits);
                }
            }
        }
        return hits;
    }

    private static void collect(String name, String constantPool, String[] prefixes, List<String> hits) {
        for (String prefix : prefixes) {
            if (constantPool.contains(prefix)) {
                hits.add(name + " -> " + prefix);
            }
        }
    }

    /**
     * Returns the class file's major version, which encodes the Java release it
     * was compiled for (55 = Java 11, 61 = Java 17, and so on).
     *
     * <p>Read straight from the header: bytes 0-3 are the 0xCAFEBABE magic and
     * bytes 6-7 are the major version, big-endian.
     */
    static int majorVersion(String className) throws IOException {
        Path classes = locateCompiledClasses();

        if (Files.isDirectory(classes)) {
            return majorVersionOf(Files.readAllBytes(classes.resolve(className)));
        }

        try (ZipFile zip = new ZipFile(classes.toFile())) {
            ZipEntry entry = zip.getEntry(className);
            if (entry == null) {
                throw new IOException("no such class in the built jar: " + className);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return majorVersionOf(readAllBytes(in));
            }
        }
    }

    private static int majorVersionOf(byte[] bytes) throws IOException {
        if (bytes.length < 8) {
            throw new IOException("truncated class file: " + bytes.length + " bytes");
        }
        return ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
    }

    /**
     * Reads a class file as ISO-8859-1, where bytes map to chars one to one, so
     * internal names stay searchable as plain ASCII.
     */
    private static String readAll(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return readAll(in);
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    /**
     * Finds the compiled library, preferring the exploded class directory and
     * falling back to the jar, so the scan works however the tests are invoked.
     */
    private static Path locateCompiledClasses() {
        Path classes = Paths.get("build", "classes", "java", "main");
        if (Files.isDirectory(classes)) {
            return classes;
        }
        File[] candidates = new File("build", "libs").listFiles(
                (dir, name) -> name.startsWith(ROOT.substring(ROOT.lastIndexOf('/') + 1)) && name.endsWith(".jar"));
        if (candidates != null && candidates.length > 0) {
            return candidates[0].toPath();
        }
        throw new IllegalStateException("cannot find the compiled library; run ./gradlew test");
    }
}
