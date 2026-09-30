package com.aaravlabs.autonomy.ftc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

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
 * wrong reading that the format argument was an {@code Object}. SDK 12.0.0
 * declares {@code addData(String, String, Object...)} -- the format is a
 * {@code String}. The call site compiled, and the published jar carried a
 * {@code Methodref} for an overload that does not exist.
 *
 * <p>{@link SdkReferenceTest} closes that hole by checking the references the
 * compiled adapter actually emits against a list transcribed from the real SDK.
 * It cannot prove the list is still correct when the SDK moves; it can prove the
 * stub has not drifted away from the list, which is the far more likely accident.
 */
final class ConstantPool {

    /**
     * Returns every field, method, and interface-method reference the named
     * class makes into {@code com/qualcomm/}, {@code org/firstinspires/}, or
     * {@code android/}, formatted as {@code owner.name:descriptor}.
     *
     * <p>Includes the class's own declarations in {@code owner.name}, so
     * references to Autonomy's own types are filtered out by the caller's
     * prefix filter rather than being special-cased here.
     */
    static Set<String> externalReferences(String className) throws IOException {
        byte[] bytes = Files.readAllBytes(locate(className));
        ConstantPool pool = new ConstantPool(bytes);
        Set<String> found = new LinkedHashSet<>();

        for (int i = 1; i < pool.entries.length; i++) {
            byte[] entry = pool.entries[i];
            if (entry == null) {
                continue;
            }
            int tag = entry[0] & 0xFF;
            if (tag != 9 && tag != 10 && tag != 11) { // Fieldref, Methodref, InterfaceMethodref
                continue;
            }
            int classIndex = pool.u2(entry, 1);
            int nameAndTypeIndex = pool.u2(entry, 3);

            String owner = pool.className(classIndex);
            String name = pool.utf8(pool.u2(pool.entries[nameAndTypeIndex], 1));
            String descriptor = pool.utf8(pool.u2(pool.entries[nameAndTypeIndex], 3));

            if (isExternal(owner)) {
                found.add(owner + "." + name + ":" + descriptor);
            }
        }
        return found;
    }

    private static boolean isExternal(String internalName) {
        return internalName.startsWith("com/qualcomm/")
                || internalName.startsWith("org/firstinspires/")
                || internalName.startsWith("android/");
    }

    private static Path locate(String className) {
        Path classes = Paths.get("build", "classes", "java", "main");
        if (Files.isDirectory(classes)) {
            return classes.resolve(className);
        }
        throw new IllegalStateException(
                "cannot find the compiled adapter at " + classes + "; run ./gradlew test");
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
     *
     * <p>A class file is header, constant pool, access/this/super/interfaces,
     * {@code fields}, {@code methods}, {@code attributes}. Fields and methods
     * have the same shape, so both tables are walked with one skipping loop.
     */
    static Set<String> declaredMethods(byte[] classFile) throws IOException {
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
        int interfaces = pool.u2(pool.bytes, at);
        at += 2 + interfaces * 2;

        int fieldCount = pool.u2(pool.bytes, at);
        at += 2;
        for (int i = 0; i < fieldCount; i++) {
            at = skipMember(pool, at);
        }

        int methodCount = pool.u2(pool.bytes, at);
        at += 2;
        Set<String> methods = new LinkedHashSet<>();
        for (int i = 0; i < methodCount; i++) {
            // access_flags(2) name(2) descriptor(2) attribute_count(2)
            String name = pool.utf8(pool.u2(pool.bytes, at + 2));
            String descriptor = pool.utf8(pool.u2(pool.bytes, at + 4));
            methods.add(name + ":" + descriptor);
            at = skipMember(pool, at);
        }
        return methods;
    }

    /** Skips one field_info or method_info, past its attributes. */
    private static int skipMember(ConstantPool pool, int at) {
        int attributeCount = pool.u2(pool.bytes, at + 6);
        at += 8;
        for (int i = 0; i < attributeCount; i++) {
            int length = pool.u4(pool.bytes, at + 2);
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
