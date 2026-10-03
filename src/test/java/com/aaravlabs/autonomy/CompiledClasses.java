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
                    String name = internalName(classes, file);
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
            int scanned = 0;
            try (Stream<Path> walk = Files.walk(classes)) {
                for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".class"))::iterator) {
                    String name = internalName(classes, file);
                    if (name.startsWith(packagePrefix)) {
                        scanned++;
                        collect(name, readAll(file), forbiddenPrefixes, hits);
                    }
                }
            }
            requireScanned(scanned, packagePrefix);
            return hits;
        }

        int scanned = 0;
        try (ZipFile zip = new ZipFile(classes.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class") || !entry.getName().startsWith(packagePrefix)) {
                    continue;
                }
                scanned++;
                try (InputStream in = zip.getInputStream(entry)) {
                    collect(entry.getName(), readAll(in), forbiddenPrefixes, hits);
                }
            }
        }
        requireScanned(scanned, packagePrefix);
        return hits;
    }

    /**
     * A class file's name relative to {@code root}, in internal form with {@code /} separators.
     */
    private static String internalName(Path root, Path file) {
        return toInternalSeparators(root.relativize(file).toString());
    }

    /**
     * Replaces the platform separator with {@code /}.
     *
     * <p>Necessary because {@link Path#relativize} hands back the platform's separator: on Windows
     * it yields {@code com\aaravlabs\autonomy\State.class}, which never matches the
     * slash-separated prefixes these scans are called with. Every class would be skipped, the scan
     * would find nothing, and the guard would report a clean library while having read nothing at
     * all -- the exact failure this class of test exists to prevent, arrived at by another route.
     *
     * <p>Separate from {@link #internalName} so the fix is reachable from a test on Linux, where
     * {@code relativize} never produces a backslash and the bug would otherwise be invisible until
     * somebody ran the build on Windows.
     */
    static String toInternalSeparators(String path) {
        return path.replace('\\', '/');
    }

    /**
     * Fails the scan when it matched no class at all.
     *
     * <p>An empty result and a result from an empty search are the same list, and only one of them
     * means anything. Without this, a renamed package, a moved build directory, or the separator
     * problem above would all read as "no problems found". Throwing is the only way to tell them
     * apart. {@code PackageBoundaryTest} asserts the same thing for the class listing.
     */
    private static void requireScanned(int scanned, String packagePrefix) throws IOException {
        if (scanned == 0) {
            throw new IOException("no compiled classes found under '" + packagePrefix
                    + "'; the scan matched nothing and would pass vacuously");
        }
    }

    private static void collect(String name, String constantPool, String[] prefixes, List<String> hits) {
        for (String prefix : prefixes) {
            if (constantPool.contains(prefix)) {
                hits.add(name + " -> " + prefix);
            }
        }
    }

    /**
     * Returns one {@code className -> owner#name} entry per forbidden method reference found in
     * the constant pool of any class under {@code packagePrefix}.
     *
     * <p>Exists because a type scan cannot see this class of problem, and {@code String.join} is
     * the example that motivated it. {@code String.join} is Android API 26, and calling it emits a
     * {@code Methodref} to {@code java/lang/String} -- the same owner that every {@code toString()}
     * in the library already references. The forbidden name {@code join} sits in the pool as its
     * own UTF8 entry with nothing to tie it to an owner, so searching the bytes for either half
     * proves nothing: the owner is always present, and the bare name would match any occurrence of
     * the substring. The pool is therefore parsed, and the owner and name resolved together.
     *
     * @param forbidden             {@code "owner#name"} pairs, e.g. {@code "java/lang/String#join"}
     * @return one entry per class and distinct method, not per call site
     */
    static List<String> findMethodReferences(String packagePrefix, String[] forbidden)
            throws IOException {
        Path classes = locateCompiledClasses();
        List<String> hits = new ArrayList<>();

        if (Files.isDirectory(classes)) {
            int scanned = 0;
            try (Stream<Path> walk = Files.walk(classes)) {
                for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".class"))::iterator) {
                    String name = internalName(classes, file);
                    if (name.startsWith(packagePrefix)) {
                        scanned++;
                        collectMethods(name, Files.readAllBytes(file), forbidden, hits);
                    }
                }
            }
            requireScanned(scanned, packagePrefix);
            return hits;
        }

        int scanned = 0;
        try (ZipFile zip = new ZipFile(classes.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class") || !entry.getName().startsWith(packagePrefix)) {
                    continue;
                }
                scanned++;
                try (InputStream in = zip.getInputStream(entry)) {
                    collectMethods(entry.getName(), readAllBytes(in), forbidden, hits);
                }
            }
        }
        requireScanned(scanned, packagePrefix);
        return hits;
    }

    private static void collectMethods(String name, byte[] bytes, String[] forbidden, List<String> hits)
            throws IOException {
        for (String rendered : forbiddenMethodReferencesIn(bytes, forbidden)) {
            hits.add(name + " -> " + rendered);
        }
    }

    /**
     * The class-file-level half of {@link #findMethodReferences}, exposed so a test can hand it
     * bytes directly.
     *
     * <p>Only the constant pool is read, so a caller may pass a header and a pool with no class
     * body after it -- which is what lets {@link NoAndroidApiLeakTest} exercise the awkward parts
     * of the parser without needing a compiler at test time.
     *
     * @param forbidden {@code "owner#name"} pairs
     * @return the distinct forbidden references, rendered as {@code owner#name}
     */
    static List<String> forbiddenMethodReferencesIn(byte[] classFile, String[] forbidden)
            throws IOException {
        ConstantPool pool = parseConstantPool(classFile);
        List<String> found = new ArrayList<>();
        for (int[] ref : pool.methodRefs) {
            String owner = pool.utf8[pool.classNameIndex[ref[0]]];
            String called = pool.utf8[pool.nameTypeNameIndex[ref[1]]];
            if (owner == null || called == null) {
                continue;
            }
            String rendered = owner + "#" + called;
            for (String candidate : forbidden) {
                if (rendered.equals(candidate) && !found.contains(rendered)) {
                    found.add(rendered);
                }
            }
        }
        return found;
    }

    /** Constant pool tags, per JVMS 4.4. */
    private static final int CONSTANT_UTF8 = 1;
    private static final int CONSTANT_INTEGER = 3;
    private static final int CONSTANT_FLOAT = 4;
    private static final int CONSTANT_LONG = 5;
    private static final int CONSTANT_DOUBLE = 6;
    private static final int CONSTANT_CLASS = 7;
    private static final int CONSTANT_STRING = 8;
    private static final int CONSTANT_FIELDREF = 9;
    private static final int CONSTANT_METHODREF = 10;
    private static final int CONSTANT_INTERFACE_METHODREF = 11;
    private static final int CONSTANT_NAME_AND_TYPE = 12;
    private static final int CONSTANT_METHOD_HANDLE = 15;
    private static final int CONSTANT_METHOD_TYPE = 16;
    private static final int CONSTANT_DYNAMIC = 17;
    private static final int CONSTANT_INVOKE_DYNAMIC = 18;
    private static final int CONSTANT_MODULE = 19;
    private static final int CONSTANT_PACKAGE = 20;

    /**
     * Just enough of a class file to resolve method references: the UTF8 strings, and the two
     * indirections that turn a {@code Methodref} into an owner and a name.
     */
    private static final class ConstantPool {

        /** UTF8 entries, indexed by constant pool index. Index 0 is unused and stays null. */
        final String[] utf8;
        /** For a Class entry, the index of its name in {@link #utf8}; 0 for every other entry. */
        final int[] classNameIndex;
        /** For a NameAndType entry, the index of the method name in {@link #utf8}. */
        final int[] nameTypeNameIndex;
        /** Every Methodref and InterfaceMethodref, as {classIndex, nameAndTypeIndex}. */
        final List<int[]> methodRefs;

        ConstantPool(String[] utf8, int[] classNameIndex, int[] nameTypeNameIndex, List<int[]> methodRefs) {
            this.utf8 = utf8;
            this.classNameIndex = classNameIndex;
            this.nameTypeNameIndex = nameTypeNameIndex;
            this.methodRefs = methodRefs;
        }
    }

    /**
     * Parses a class file's constant pool.
     *
     * <p>Walks the entries in order, which is the only way to find where the next one starts,
     * since the only variable-length entry is UTF8. Long and Double take two pool slots, so the
     * index counter skips one for them -- missing that would misalign every entry after the first
     * double literal and silently report the wrong owner, which is the quietest way this could
     * fail.
     *
     * @throws IOException if the class file is truncated or carries an unknown tag
     */
    private static ConstantPool parseConstantPool(byte[] bytes) throws IOException {
        if (bytes.length < 10) {
            throw new IOException("truncated class file: " + bytes.length + " bytes");
        }
        int cpCount = ((bytes[8] & 0xFF) << 8) | (bytes[9] & 0xFF);

        String[] utf8 = new String[cpCount];
        int[] classNameIndex = new int[cpCount];
        int[] nameTypeNameIndex = new int[cpCount];
        List<int[]> methodRefs = new ArrayList<>();

        int at = 10;
        for (int index = 1; index < cpCount; index++) {
            if (at >= bytes.length) {
                throw new IOException("truncated constant pool at entry " + index);
            }
            int tag = bytes[at] & 0xFF;
            switch (tag) {
                case CONSTANT_UTF8: {
                    if (at + 3 > bytes.length) {
                        throw new IOException("truncated UTF8 entry " + index);
                    }
                    int length = ((bytes[at + 1] & 0xFF) << 8) | (bytes[at + 2] & 0xFF);
                    if (at + 3 + length > bytes.length) {
                        throw new IOException("truncated UTF8 entry " + index);
                    }
                    utf8[index] = new String(bytes, at + 3, length, StandardCharsets.ISO_8859_1);
                    at += 3 + length;
                    break;
                }
                case CONSTANT_CLASS:
                    classNameIndex[index] = u2(bytes, at + 1);
                    at += 3;
                    break;
                case CONSTANT_NAME_AND_TYPE:
                    // tag, then name_index, then descriptor_index. A Methodref refers to the
                    // name; the descriptor is only the signature.
                    nameTypeNameIndex[index] = u2(bytes, at + 1);
                    at += 5;
                    break;
                case CONSTANT_METHODREF:
                case CONSTANT_INTERFACE_METHODREF:
                    methodRefs.add(new int[] {u2(bytes, at + 1), u2(bytes, at + 3)});
                    at += 5;
                    break;
                case CONSTANT_STRING:
                case CONSTANT_METHOD_TYPE:
                case CONSTANT_MODULE:
                case CONSTANT_PACKAGE:
                    at += 3;
                    break;
                case CONSTANT_FIELDREF:
                case CONSTANT_INTEGER:
                case CONSTANT_FLOAT:
                case CONSTANT_DYNAMIC:
                case CONSTANT_INVOKE_DYNAMIC:
                    at += 5;
                    break;
                case CONSTANT_METHOD_HANDLE:
                    at += 4;
                    break;
                case CONSTANT_LONG:
                case CONSTANT_DOUBLE:
                    at += 9;
                    index++;
                    break;
                default:
                    throw new IOException("unknown constant pool tag " + tag + " at entry " + index);
            }
        }
        return new ConstantPool(utf8, classNameIndex, nameTypeNameIndex, methodRefs);
    }

    private static int u2(byte[] bytes, int at) {
        return ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
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
