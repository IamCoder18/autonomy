package com.aaravlabs.autonomy.ftc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads the constant pool of a compiled class and reports every reference it
 * makes into the FTC SDK or Android.
 *
 * <p>Autonomy compiles its robot-facing class against hand-written stubs rather
 * than the real SDK, because the real artifacts are AARs that a
 * {@code java-library} project cannot put on a javac classpath. That is a
 * deliberate trade, and it has one dangerous failure mode: a stub that declares
 * something the real SDK does not have compiles perfectly, passes every test,
 * and then throws {@code NoSuchMethodError} on a robot.
 *
 * <p>That is not hypothetical. An early version of the {@code Telemetry} stub
 * declared {@code addData(String, Object, Object...)}, on the reasonable but
 * wrong reading that the format argument was an {@code Object}. SDK 11.2.1
 * declares {@code addData(String, String, Object...)} -- the format is a
 * {@code String}. The call site compiled, and the published jar carried a
 * {@code Methodref} for an overload that does not exist.
 *
 * <p>{@link SdkReferenceTest} closes that hole by checking the references the
 * compiled adapter actually emits against the real SDK. It cannot prove the stub
 * is still faithful when the SDK moves; {@link FtcStubFidelityTest} does that,
 * by reading the AAR.
 */
final class ConstantPool {

    /**
     * Every field, method, and interface-method reference the named class makes into the FTC
     * SDK, formatted as {@code owner.name:descriptor}.
     *
     * <p><strong>Classifying a reference as "the SDK" is the whole difficulty here,</strong>
     * and neither half of it can be left to a prefix test:
     *
     * <ul>
     * <li>An <em>owner</em> prefix test misses inherited members. javac emits the
     *     <em>qualifying</em> type as the owner, not the declaring one, so
     *     {@code this.telemetry} and an unqualified {@code requestOpModeStop()} come out
     *     with {@code StateMachineOpMode} as their owner:
     *
     *     <pre>{@code
     *     Fieldref  StateMachineOpMode.telemetry : Lorg/firstinspires/.../Telemetry;
     *     Methodref StateMachineOpMode.requestOpModeStop : ()V
     *     }</pre>
     *
     *     Those resolve at runtime -- the JVM walks the superclass chain, JVMS 5.4.3.2 --
     *     but an owner-prefix test drops them, which is how the one SDK <em>field</em> the
     *     adapter reads went unverified, and how {@code requestOpModeStop()} went with it.
     *
     * <li>A <em>descriptor</em> prefix test misses the same references from the other side.
     *     {@code requestOpModeStop}'s descriptor is {@code ()V}, which names no type at all.
     * </ul>
     *
     * <p>So the rule is structural: a reference owned by the adapter belongs to the SDK if
     * the adapter does not declare it itself, because everything the adapter does not
     * declare has to come from one of its superclasses. Everything else is classified by
     * owner.
     *
     * <p>{@code ignoreOwningClass} is the adapter, and {@code ignorePrefixes} are the trees
     * that are not the robot's problem here: the framework's own classes (covered by
     * {@code PackageBoundaryTest}) and the JDK.
     */
    static Set<String> sdkReferences(
            String classFile, String ignoreOwningClass, String... ignorePrefixes)
            throws IOException {
        byte[] bytes = Files.readAllBytes(locate(classFile));
        ConstantPool pool = new ConstantPool(bytes);
        Set<String> ownMembers = pool.declaredMembers(bytes).all();
        Set<String> found = new LinkedHashSet<>();

        for (byte[] entry : pool.entries) {
            if (entry == null) {
                continue;
            }
            int tag = entry[0] & 0xFF;
            if (tag != 9 && tag != 10 && tag != 11) { // Fieldref, Methodref, InterfaceMethodref
                continue;
            }
            int nameAndTypeIndex = ConstantPool.u2(entry, 3);
            String owner = pool.className(ConstantPool.u2(entry, 1));
            String name = pool.utf8(ConstantPool.u2(pool.entries[nameAndTypeIndex], 1));
            String descriptor = pool.utf8(ConstantPool.u2(pool.entries[nameAndTypeIndex], 3));
            String reference = owner + "." + name + ":" + descriptor;

            if (owner.equals(ignoreOwningClass)) {
                // Inherited: anything the adapter declares itself is its own business.
                if (!ownMembers.contains(name + ":" + descriptor)) {
                    found.add(reference);
                }
                continue;
            }
            boolean ignored = false;
            for (String prefix : ignorePrefixes) {
                if (owner.startsWith(prefix)) {
                    ignored = true;
                    break;
                }
            }
            if (!ignored) {
                found.add(reference);
            }
        }
        return found;
    }

    /**
     * The internal name of a class's superclass, or {@code null} for {@code java/lang/Object}
     * and the class file's root.
     *
     * <p>Read from the class file rather than through {@code Class.getSuperclass()} on purpose:
     * the whole point is to walk the <em>real</em> SDK's hierarchy, and the only real SDK on
     * this machine is the extracted {@code classes.jar}.
     */
    static String superName(byte[] classFile) throws IOException {
        ConstantPool pool = new ConstantPool(classFile);

        int at = 10;
        for (byte[] entry : pool.entries) {
            if (entry != null) {
                at += entry.length;
            }
        }
        at += 2;   // access_flags
        at += 2;   // this_class
        int superIndex = ConstantPool.u2(pool.bytes, at);
        if (superIndex == 0) {
            return null;
        }
        String name = pool.className(superIndex);
        return name.equals("java/lang/Object") ? null : name;
    }

    /**
     * Lists every compiled class under an internal package prefix, e.g.
     * {@code com/aaravlabs/autonomy/ftc/}.
     *
     * <p>Scanning one named class file is not enough. javac puts a lambda's body in the
     * enclosing class, but only because it usually can: {@code invokedynamic} may also
     * emit a separate synthetic holder, and anything the compiler chose to place there
     * would carry its own constant pool. A guard that reads one file has to trust that
     * decision; one that reads the package does not.
     */
    static List<String> listClasses(String internalPackagePrefix) throws IOException {
        Path classes = compiledClassesDirectory();
        List<String> names = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(classes)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> {
                        // Normalise to '/' before matching: relativize() yields the platform
                        // separator, so on Windows this is "com\aaravlabs\...", which never
                        // matches a slash-separated internal-name prefix. Without this the scan
                        // finds nothing and throws, failing the SDK-reference tests on a
                        // platform nobody here runs CI on.
                        String name = classes.relativize(p).toString().replace('\\', '/');
                        // Strip .class: these are internal names, which is what callers
                        // must compare against constant pool owners.
                        if (name.startsWith(internalPackagePrefix)) {
                            names.add(name.substring(0, name.length() - ".class".length()));
                        }
                    });
        }
        if (names.isEmpty()) {
            throw new IOException("no compiled classes under " + internalPackagePrefix
                    + "; run ./gradlew test");
        }
        return names;
    }

    private static Path compiledClassesDirectory() {
        Path classes = Paths.get("build", "classes", "java", "main");
        if (!Files.isDirectory(classes)) {
            throw new IllegalStateException(
                    "cannot find compiled classes at " + classes + "; run ./gradlew test");
        }
        return classes;
    }

    private static Path locate(String className) {
        return compiledClassesDirectory().resolve(className + ".class");
    }

    /**
     * Reads the {@code methods} table of a class file and returns every declared
     * method as {@code name:descriptor}.
     *
     * <p>Descriptors rather than Java signatures, because the descriptor is what
     * the JVM matches. {@code boolean update()} and {@code void update()} are both
     * "update()" to anyone reading source, and {@code update()Z} versus
     * {@code update()V} to the linker -- and only the second distinction is fatal.
     * That gap is exactly how a wrong return type reaches a Robot Controller with
     * every test green.
     */
    static Set<String> declaredMethods(byte[] classFile) throws IOException {
        return declaredMembers(classFile).methods;
    }

    /**
     * Reads the {@code fields} table of a class file and returns every declared
     * field as {@code name:descriptor}.
     *
     * <p>Fields matter for the same reason methods do. {@code OpModeInternal} is
     * where the SDK declares {@code telemetry} and {@code hardwareMap}, and a stub
     * that declared them on {@code OpMode} instead would compile and then throw
     * {@code NoSuchFieldError} the first time an OpMode reported telemetry. For a
     * long time nothing here checked fields at all.
     */
    static Set<String> declaredFields(byte[] classFile) throws IOException {
        return declaredMembers(classFile).fields;
    }

    /** The declared fields and methods of a class, read in one pass over the class file. */
    static Members declaredMembers(byte[] classFile) throws IOException {
        ConstantPool pool = new ConstantPool(classFile);

        int at = 10;
        for (byte[] entry : pool.entries) {
            if (entry != null) {
                at += entry.length;   // null is the second slot of a long or double
            }
        }

        at += 2;   // access_flags
        at += 2;   // this_class
        at += 2;   // super_class
        int interfaces = ConstantPool.u2(pool.bytes, at);
        at += 2 + interfaces * 2;

        int fieldCount = ConstantPool.u2(pool.bytes, at);
        at += 2;
        Set<String> fields = new LinkedHashSet<>();
        for (int i = 0; i < fieldCount; i++) {
            fields.add(pool.utf8(ConstantPool.u2(pool.bytes, at + 2)) + ":"
                    + pool.utf8(ConstantPool.u2(pool.bytes, at + 4)));
            at = skipMember(pool, at);
        }

        int methodCount = ConstantPool.u2(pool.bytes, at);
        at += 2;
        Set<String> methods = new LinkedHashSet<>();
        for (int i = 0; i < methodCount; i++) {
            // access_flags(2) name(2) descriptor(2) attribute_count(2)
            methods.add(pool.utf8(ConstantPool.u2(pool.bytes, at + 2)) + ":"
                    + pool.utf8(ConstantPool.u2(pool.bytes, at + 4)));
            at = skipMember(pool, at);
        }
        return new Members(fields, methods);
    }

    /** Declared fields and methods of one class file. */
    static final class Members {

        final Set<String> fields;
        final Set<String> methods;

        Members(Set<String> fields, Set<String> methods) {
            this.fields = fields;
            this.methods = methods;
        }

        /** Every declared member as {@code name:descriptor}, methods and fields together. */
        Set<String> all() {
            Set<String> every = new LinkedHashSet<>(methods);
            every.addAll(fields);
            return every;
        }
    }

    /** Skips one field_info or method_info, past its attributes. */
    private static int skipMember(ConstantPool pool, int at) {
        int attributeCount = ConstantPool.u2(pool.bytes, at + 6);
        at += 8;
        for (int i = 0; i < attributeCount; i++) {
            int length = ConstantPool.u4(pool.bytes, at + 2);
            at += 6 + length;
        }
        return at;
    }

    // ---- constant pool decoding ------------------------------------------------

    private final byte[] bytes;
    private final byte[][] entries;
    private final String[] strings;

    private ConstantPool(byte[] bytes) throws IOException {
        this.bytes = bytes;
        if (bytes.length < 10 || u4(bytes, 0) != 0xCAFEBABE) {
            throw new IOException("not a class file");
        }
        int count = u2(bytes, 8);

        this.entries = new byte[count][];
        this.strings = new String[count];

        int at = 10;
        for (int i = 1; i < count; i++) {
            int tag = bytes[at] & 0xFF;
            int size;
            if (tag == 1) {
                // Utf8 carries its own length, so its size is not a constant.
                size = 3 + u2(bytes, at + 1);
            } else {
                size = sizeOf(tag);
            }
            byte[] entry = new byte[size];
            System.arraycopy(bytes, at, entry, 0, size);
            entries[i] = entry;
            at += size;
            // long and double occupy two constant pool slots.
            if (tag == 5 || tag == 6) {
                i++;
            }
        }
    }

    private static int sizeOf(int tag) throws IOException {
        switch (tag) {
            case 7:   // Class
            case 8:   // String
            case 16:  // MethodType
            case 19:  // Module
            case 20:  // Package
                return 3;
            case 15:  // MethodHandle
                return 4;
            case 3:   // Integer
            case 4:   // Float
            case 9:   // Fieldref
            case 10:  // Methodref
            case 11:  // InterfaceMethodref
            case 12:  // NameAndType
            case 17:  // Dynamic
            case 18:  // InvokeDynamic
                return 5;
            case 5:   // Long
            case 6:   // Double
                return 9;
            default:
                throw new IOException("unknown constant pool tag " + tag);
        }
    }

    private String className(int index) {
        byte[] entry = entries[index];
        if (entry == null || (entry[0] & 0xFF) != 7) {
            throw new IllegalStateException("constant pool index " + index + " is not a Class");
        }
        return utf8(u2(entry, 1));
    }

    private String utf8(int index) {
        if (strings[index] != null) {
            return strings[index];
        }
        byte[] entry = entries[index];
        int length = u2(entry, 1);
        // Class files are modified UTF-8; the names compared here are ASCII, so
        // a byte-per-char decode is exact for every case that matters.
        String value = new String(entry, 3, length, java.nio.charset.StandardCharsets.ISO_8859_1);
        strings[index] = value;
        return value;
    }

    private static int u2(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int u4(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }
}