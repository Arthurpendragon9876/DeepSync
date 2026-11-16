import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Restores constructors that R8 removed from extension classes.
 *
 * R8 deletes the empty constructor of a class (usually an abstract base) and drops the calls to it, which is legal
 * on Android. The APK -> JAR conversion has to add super(...) calls back because the JVM requires them, so the
 * subclass ends up calling a constructor that no longer exists (NoSuchMethodError: X.<init>()V), and every class
 * that touches it fails with NoClassDefFoundError. This adds an empty public no-arg constructor to every such class.
 *
 * Usage: CtorFix in.jar out.jar      Prints "fixed N" on stdout.
 */
public class CtorFix {
    static final String MARKER = "META-INF/deepsync-ctorfix-v1";

    public static void main(String[] args) throws Exception {
        File first = new File(args[0]);
        if (args.length == 1 && first.isDirectory()) {
            // Directory mode: fix every JAR in place; JARs that already carry the marker are left alone.
            File[] jars = first.listFiles((d, n) -> n.endsWith(".jar"));
            int total = 0;
            if (jars != null) for (File j : jars) {
                try { total += process(j, j); } catch (Exception e) { System.err.println("ctorfix skipped " + j.getName() + ": " + e); }
            }
            System.out.println("fixed " + total);
            return;
        }
        System.out.println("fixed " + process(first, new File(args[1])));
    }

    static int process(File in, File out) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(in))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                if (!e.isDirectory()) entries.put(e.getName(), zin.readAllBytes());
            }
        }
        if (entries.containsKey(MARKER)) { copy(in, out); return 0; }

        Map<String, ClassNode> nodes = new HashMap<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (!e.getKey().endsWith(".class")) continue;
            try {
                ClassNode cn = new ClassNode();
                new ClassReader(e.getValue()).accept(cn, ClassReader.SKIP_CODE);
                nodes.put(cn.name, cn);
            } catch (Exception ignored) { }
        }
        // A class needs a constructor when it is a real class with none at all.
        Set<String> missing = new HashSet<>();
        for (ClassNode cn : nodes.values()) {
            boolean special = (cn.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION | Opcodes.ACC_MODULE)) != 0;
            if (special || cn.name.equals("module-info")) continue;
            boolean hasInit = false;
            for (MethodNode m : cn.methods) if (m.name.equals("<init>")) { hasInit = true; break; }
            if (!hasInit) missing.add(cn.name);
        }
        // Only when the super constructor we would call exists (Object, or another class that has/gets one).
        Set<String> fix = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String name : missing) {
                if (fix.contains(name)) continue;
                String sup = nodes.get(name).superName;
                boolean ok = sup == null || sup.equals("java/lang/Object") || fix.contains(sup);
                if (!ok && nodes.containsKey(sup)) {
                    for (MethodNode m : nodes.get(sup).methods) if (m.name.equals("<init>") && m.desc.equals("()V")) ok = true;
                }
                if (ok) { fix.add(name); changed = true; }
            }
        }

        int count = 0;
        for (String name : fix) {
            String path = name + ".class";
            byte[] old = entries.get(path);
            if (old == null) continue;
            ClassReader cr = new ClassReader(old);
            ClassWriter cw = new ClassWriter(0);
            final String sup = nodes.get(name).superName == null ? "java/lang/Object" : nodes.get(name).superName;
            cr.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9, cw) {
                @Override public void visitEnd() {
                    MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
                    mv.visitCode();
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, sup, "<init>", "()V", false);
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(1, 1);
                    mv.visitEnd();
                    super.visitEnd();
                }
            }, 0);
            entries.put(path, cw.toByteArray());
            count++;
        }
        entries.put(MARKER, "1".getBytes());
        File tmp = new File(out.getPath() + ".tmp");
        try (ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(tmp))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zout.putNextEntry(new ZipEntry(e.getKey()));
                zout.write(e.getValue());
                zout.closeEntry();
            }
        }
        if (out.exists() && !out.delete()) throw new IOException("cannot replace " + out);
        if (!tmp.renameTo(out)) throw new IOException("cannot write " + out);
        return count;
    }

    static void copy(File a, File b) throws IOException {
        if (a.equals(b)) return;
        try (InputStream i = new FileInputStream(a); OutputStream o = new FileOutputStream(b)) { i.transferTo(o); }
    }
}
