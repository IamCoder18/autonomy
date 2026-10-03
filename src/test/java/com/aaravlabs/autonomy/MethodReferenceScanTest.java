package com.aaravlabs.autonomy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests the bytecode scan that catches API 26 <em>method</em> references.
 *
 * <p>{@link NoAndroidApiLeakTest} can only assert that the shipped library is clean, which is
 * worth nothing if the scan underneath it reports nothing for any input at all. A guard that
 * cannot fail is not a guard, so these build class files by hand and assert the scan finds what
 * they contain.
 *
 * <p>Only a header and a constant pool are emitted, because that is all
 * {@link CompiledClasses#forbiddenMethodReferencesIn} reads. That is what makes this possible
 * without a compiler at test time -- and it is why the two awkward parts of the parser can be
 * pinned at all: a wide constant ahead of the reference, which occupies two pool slots and would
 * misalign every entry after it if the counter did not skip, and a bare {@code join} name with no
 * owner, which is what the type-level scan was silently matching on nothing.
 */
class MethodReferenceScanTest {

    private static final String[] FORBIDDEN = {"java/lang/String#join"};

    @Test
    @DisplayName("finds a forbidden method reference and names it")
    void findsAForbiddenMethodReference() throws IOException {
        byte[] classFile = PoolBuilder.classFile()
                .methodRef(String.join("()Ljava/lang/String;"))
                .build();

        assertEquals(Arrays.asList("java/lang/String#join"),
                CompiledClasses.forbiddenMethodReferencesIn(classFile, FORBIDDEN));
    }

    @Test
    @DisplayName("still finds it after a long constant, which takes two constant pool slots")
    void survivesAWideConstantBeforeTheReference() throws IOException {
        // A Long or Double entry claims two pool indexes. A parser that forgets to skip the spare
        // one reads every entry after it as garbage, and would report either nothing or the wrong
        // owner -- the quietest possible failure for a guard.
        byte[] classFile = PoolBuilder.classFile()
                .longConstant()
                .methodRef(String.join("()Ljava/lang/String;"))
                .build();

        assertEquals(Arrays.asList("java/lang/String#join"),
                CompiledClasses.forbiddenMethodReferencesIn(classFile, FORBIDDEN));
    }

    @Test
    @DisplayName("still finds it after several wide constants")
    void survivesSeveralWideConstants() throws IOException {
        byte[] classFile = PoolBuilder.classFile()
                .longConstant()
                .longConstant()
                .doubleConstant()
                .methodRef(String.join("()Ljava/lang/String;"))
                .build();

        assertEquals(Arrays.asList("java/lang/String#join"),
                CompiledClasses.forbiddenMethodReferencesIn(classFile, FORBIDDEN));
    }

    @Test
    @DisplayName("ignores a name with no owner that resolves to a class")
    void ignoresANamePointedAtSomethingOtherThanAClass() throws IOException {
        // This is the trap the type-level scan falls into: "join" on its own is a string that
        // proves nothing, and java/lang/String is an owner the whole library references. A
        // Methodref whose class slot points at a UTF8 entry has no resolvable owner, and must not
        // be reported as a match rather than crashing or guessing.
        byte[] classFile = PoolBuilder.classFile()
                .methodRef("()Ljava/lang/String;", true)
                .build();

        assertTrue(CompiledClasses.forbiddenMethodReferencesIn(classFile, FORBIDDEN).isEmpty(),
                "a reference whose owner does not resolve must not be reported");
    }

    @Test
    @DisplayName("ignores a method of the right name on the wrong owner")
    void ignoresTheRightNameOnTheWrongOwner() throws IOException {
        byte[] classFile = PoolBuilder.classFile()
                .methodRef("java/lang/StringBuilder#join ()Ljava/lang/String;")
                .build();

        assertTrue(CompiledClasses.forbiddenMethodReferencesIn(classFile, FORBIDDEN).isEmpty(),
                "StringBuilder#join is not java/lang/String#join");
    }

    @Test
    @DisplayName("ignores a safe String method")
    void ignoresASafeStringMethod() throws IOException {
        byte[] classFile = PoolBuilder.classFile()
                .methodRef("java/lang/String#valueOf (I)Ljava/lang/String;")
                .build();

        assertTrue(CompiledClasses.forbiddenMethodReferencesIn(classFile, FORBIDDEN).isEmpty(),
                "String.valueOf is API 1 and must not be reported");
    }

    @Test
    @DisplayName("reports one entry per method, however many times it is called")
    void reportsEachMethodOnce() throws IOException {
        byte[] classFile = PoolBuilder.classFile()
                .methodRef(String.join("()Ljava/lang/String;"))
                .methodRef(String.join("()Ljava/lang/String;"))
                .build();

        assertEquals(Arrays.asList("java/lang/String#join"),
                CompiledClasses.forbiddenMethodReferencesIn(classFile, FORBIDDEN),
                "two call sites of one method are one finding, not two");
    }

    @Test
    @DisplayName("finds two different methods on the same owner")
    void findsEachDistinctMethod() throws IOException {
        String[] forbidden = {"java/lang/String#join", "java/lang/String#valueOf"};

        byte[] classFile = PoolBuilder.classFile()
                .methodRef(String.join("()Ljava/lang/String;"))
                .methodRef("java/lang/String#valueOf (I)Ljava/lang/String;")
                .build();

        assertEquals(Arrays.asList("java/lang/String#join", "java/lang/String#valueOf"),
                CompiledClasses.forbiddenMethodReferencesIn(classFile, forbidden));
    }

    @Test
    @DisplayName("rejects a truncated class file rather than reporting nothing")
    void rejectsATruncatedClassFile() {
        byte[] truncated = new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 55};

        try {
            CompiledClasses.forbiddenMethodReferencesIn(truncated, FORBIDDEN);
            fail("a truncated class file should fail loudly, not scan as clean");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("truncated"), expected.getMessage());
        }
    }

    /**
     * Emits a class file's header and constant pool.
     *
     * <p>Every builder method returns the constant pool index it wrote, so a test wires entries
     * together by threading the returned index rather than counting them by hand. Hand-counting is
     * how the two-slot case gets wrong twice: once in the expectation and once in the fixture,
     * which then agree with each other and prove nothing.
     */
    private static final class PoolBuilder {

        private static final int TAG_UTF8 = 1;
        private static final int TAG_LONG = 5;
        private static final int TAG_DOUBLE = 6;
        private static final int TAG_CLASS = 7;
        private static final int TAG_METHODREF = 10;
        private static final int TAG_NAME_AND_TYPE = 12;

        private static final String DEFAULT_OWNER = "java/lang/String";
        private static final String DEFAULT_NAME = "join";

        private final ByteArrayOutputStream pool = new ByteArrayOutputStream();
        private int nextIndex = 1;

        static PoolBuilder classFile() {
            return new PoolBuilder();
        }

        /**
         * Appends a Methodref for {@code owner#name descriptor}.
         *
         * <p>Two forms, so a test can be as specific as it needs to be: a bare descriptor uses
         * {@code java/lang/String#join} and is the common case, while {@code owner#name
         * descriptor} names both. The space before the descriptor is required in the second form,
         * because {@code valueOf(I)Ljava/lang/String;} does not say on its own where the name ends.
         *
         * @param ownerSlotIsUtf8 point the reference's class slot at a UTF8 entry instead of a
         *                       Class entry, which is a reference with no resolvable owner
         */
        PoolBuilder methodRef(String signature, boolean ownerSlotIsUtf8) {
            int hash = signature.indexOf('#');
            String owner = hash < 0 ? DEFAULT_OWNER : signature.substring(0, hash);

            String methodName;
            String descriptor;
            if (hash < 0) {
                methodName = DEFAULT_NAME;
                descriptor = signature;
            } else {
                String rest = signature.substring(hash + 1);
                int space = rest.indexOf(' ');
                methodName = space < 0 ? rest : rest.substring(0, space);
                descriptor = space < 0 ? "" : rest.substring(space + 1);
            }

            int ownerIndex = ownerSlotIsUtf8 ? utf8(owner) : classEntry(owner);
            int nameType = nameAndType(methodName, descriptor);
            return write(TAG_METHODREF, ownerIndex, nameType);
        }

        PoolBuilder methodRef(String signature) {
            return methodRef(signature, false);
        }

        /** A Long entry: nine bytes, and two constant pool slots. */
        PoolBuilder longConstant() {
            writeByte(TAG_LONG);
            writeBytes(1L);
            return skip(2);
        }

        /** A Double entry: nine bytes, and two constant pool slots. */
        PoolBuilder doubleConstant() {
            writeByte(TAG_DOUBLE);
            writeBytes(Double.doubleToLongBits(2.5d));
            return skip(2);
        }

        byte[] build() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0xCA);
            out.write(0xFE);
            out.write(0xBA);
            out.write(0xBE);                                        // magic
            out.write(0);
            out.write(0);                                            // minor version
            out.write(0);
            out.write(55);                                           // major: Java 11
            writeShortTo(out, nextIndex);                            // constant_pool_count
            pool.writeTo(out);
            return out.toByteArray();
        }

        private int utf8(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.ISO_8859_1);
            writeByte(TAG_UTF8);
            writeShort(bytes.length);
            pool.write(bytes, 0, bytes.length);
            return claimed(1);
        }

        private int classEntry(String internalName) {
            int nameIndex = utf8(internalName);
            writeByte(TAG_CLASS);
            writeShort(nameIndex);
            return claimed(1);
        }

        private int nameAndType(String methodName, String methodDescriptor) {
            int nameIndex = utf8(methodName);
            int descriptorIndex = utf8(methodDescriptor);
            writeByte(TAG_NAME_AND_TYPE);
            writeShort(nameIndex);
            writeShort(descriptorIndex);
            return claimed(1);
        }

        private PoolBuilder write(int tag, int first, int second) {
            writeByte(tag);
            writeShort(first);
            writeShort(second);
            claimed(1);
            return this;
        }

        /** Records that {@code slots} indexes have been consumed and yields the first of them. */
        private int claimed(int slots) {
            int first = nextIndex;
            nextIndex += slots;
            return first;
        }

        private PoolBuilder skip(int slots) {
            nextIndex += slots;
            return this;
        }

        private void writeByte(int value) {
            pool.write(value);
        }

        private void writeShort(int value) {
            writeShortTo(pool, value);
        }

        /** Eight bytes, big-endian: the u2 high half followed by the u4 low half. */
        private void writeBytes(long value) {
            for (int shift = 56; shift >= 0; shift -= 8) {
                pool.write((int) (value >> shift) & 0xFF);
            }
        }

        private static void writeShortTo(ByteArrayOutputStream out, int value) {
            out.write((value >> 8) & 0xFF);
            out.write(value & 0xFF);
        }
    }
}