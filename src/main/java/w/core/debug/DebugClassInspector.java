package w.core.debug;

import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.extern.IBytecodeProvider;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;
import w.Global;
import w.core.ClassTargetResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;

final class DebugClassInspector {
    Inspection inspect(Class<?> target) throws Exception {
        if (!Global.isModifiableClass(target)) {
            throw new IllegalArgumentException("Target class is not modifiable: " + target.getName());
        }
        byte[] bytes = OriginalBytecodeObserver.capture(target);
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
        DecompileResult decompiled = decompile(target.getName(), bytes);

        Map<Integer, Integer> displayToOriginal = new LinkedHashMap<>();
        Set<Integer> ambiguousDisplayLines = new LinkedHashSet<>();
        if (decompiled.mapping != null) {
            for (int i = 0; i + 1 < decompiled.mapping.length; i += 2) {
                int display = decompiled.mapping[i + 1];
                int original = decompiled.mapping[i];
                Integer previous = displayToOriginal.get(display);
                if (ambiguousDisplayLines.contains(display)) {
                    continue;
                }
                if (previous != null && previous != original) {
                    displayToOriginal.remove(display);
                    ambiguousDisplayLines.add(display);
                } else {
                    displayToOriginal.put(display, original);
                }
            }
        }

        List<MethodInfo> methods = new ArrayList<>();
        for (MethodNode method : node.methods) {
            Set<Integer> originals = new LinkedHashSet<>();
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                 instruction != null; instruction = instruction.getNext()) {
                if (instruction instanceof LineNumberNode) {
                    originals.add(((LineNumberNode) instruction).line);
                }
            }
            if (originals.isEmpty()) {
                continue;
            }
            List<Integer> originalLines = new ArrayList<>(originals);
            Collections.sort(originalLines);
            List<Integer> displayedLines = new ArrayList<>();
            for (Map.Entry<Integer, Integer> entry : displayToOriginal.entrySet()) {
                if (originals.contains(entry.getValue())) {
                    displayedLines.add(entry.getKey());
                }
            }
            Collections.sort(displayedLines);
            boolean supported = !"<init>".equals(method.name) && !"<clinit>".equals(method.name);
            int[] displayRange = methodDisplayRange(
                    decompiled.source, target.getName(), method.name, displayedLines);
            methods.add(new MethodInfo(method.name, method.desc, originalLines, displayedLines,
                    displayRange[0], displayRange[1], supported));
        }
        Map<Integer, Integer> methodOwners = new HashMap<>();
        for (MethodInfo method : methods) {
            for (Integer originalLine : method.originalLines) {
                methodOwners.put(originalLine,
                        methodOwners.getOrDefault(originalLine, 0) + 1);
            }
        }
        List<Integer> crossMethodAmbiguities = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : displayToOriginal.entrySet()) {
            if (methodOwners.getOrDefault(entry.getValue(), 0) > 1) {
                crossMethodAmbiguities.add(entry.getKey());
            }
        }
        for (Integer displayLine : crossMethodAmbiguities) {
            displayToOriginal.remove(displayLine);
            ambiguousDisplayLines.add(displayLine);
            for (MethodInfo method : methods) {
                method.displayedLines.remove(displayLine);
            }
        }
        methods.sort(Comparator.comparingInt(MethodInfo::firstOriginalLine));
        return new Inspection(
                target, decompiled.source, displayToOriginal, ambiguousDisplayLines, methods);
    }

    private int[] methodDisplayRange(String source, String className, String methodName,
                                     List<Integer> executableLines) {
        if (executableLines.isEmpty()) {
            return new int[]{-1, -1};
        }
        String[] lines = source.split("\\r?\\n", -1);
        int first = executableLines.get(0);
        int last = executableLines.get(executableLines.size() - 1);
        String simpleClass = className.substring(className.lastIndexOf('.') + 1);
        String needle = "<init>".equals(methodName) ? simpleClass + "(" : methodName + "(";
        int signatureLine = -1;
        for (int line = Math.min(first - 1, lines.length - 1);
             line >= Math.max(0, first - 30); line--) {
            if (lines[line].contains(needle)) {
                signatureLine = line;
                break;
            }
        }
        if (signatureLine < 0) {
            return new int[]{first, last};
        }
        int openLine = -1;
        int openColumn = -1;
        for (int line = signatureLine; line < Math.min(first, lines.length); line++) {
            int column = lines[line].indexOf('{');
            if (column >= 0) {
                openLine = line;
                openColumn = column;
                break;
            }
        }
        if (openLine < 0) {
            return new int[]{first, last};
        }
        return new int[]{signatureLine + 1,
                Math.max(last, matchingCloseLine(lines, openLine, openColumn) + 1)};
    }

    private int matchingCloseLine(String[] lines, int openLine, int openColumn) {
        int depth = 0;
        boolean blockComment = false;
        for (int line = openLine; line < lines.length; line++) {
            boolean string = false;
            boolean character = false;
            boolean escaped = false;
            for (int column = line == openLine ? openColumn : 0;
                 column < lines[line].length(); column++) {
                char current = lines[line].charAt(column);
                char next = column + 1 < lines[line].length()
                        ? lines[line].charAt(column + 1) : '\0';
                if (blockComment) {
                    if (current == '*' && next == '/') {
                        blockComment = false;
                        column++;
                    }
                    continue;
                }
                if (!string && !character && current == '/' && next == '*') {
                    blockComment = true;
                    column++;
                    continue;
                }
                if (!string && !character && current == '/' && next == '/') {
                    break;
                }
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if ((string || character) && current == '\\') {
                    escaped = true;
                    continue;
                }
                if (!character && current == '"') {
                    string = !string;
                    continue;
                }
                if (!string && current == '\'') {
                    character = !character;
                    continue;
                }
                if (string || character) {
                    continue;
                }
                if (current == '{') {
                    depth++;
                } else if (current == '}' && --depth == 0) {
                    return line;
                }
            }
        }
        return Math.max(openLine, lines.length - 1);
    }

    private DecompileResult decompile(String className, byte[] bytes) throws IOException {
        Map<String, Object> options = new HashMap<>();
        options.put("cps", "1");
        options.put("crp", "1");
        options.put("bsm", "1");
        ResultSaver saver = new ResultSaver(className);
        IBytecodeProvider provider = (externalPath, internalPath) -> {
            throw new IOException("external bytecode lookup is disabled");
        };
        Fernflower fernflower = new Fernflower(provider, saver, options, new SilentLogger());
        try {
            fernflower.addData("", className.replace('.', '/') + ".class", bytes, true);
            fernflower.decompileContext();
        } finally {
            fernflower.clearContext();
        }
        if (saver.source == null) {
            throw new IllegalStateException("Fernflower returned no source for " + className);
        }
        return new DecompileResult(saver.source, saver.mapping);
    }

    static final class Inspection {
        private final Class<?> target;
        private final String source;
        private final Map<Integer, Integer> displayToOriginal;
        private final Set<Integer> ambiguousDisplayLines;
        private final List<MethodInfo> methods;

        private Inspection(Class<?> target, String source, Map<Integer, Integer> displayToOriginal,
                           Set<Integer> ambiguousDisplayLines, List<MethodInfo> methods) {
            this.target = target;
            this.source = source;
            this.displayToOriginal = displayToOriginal;
            this.ambiguousDisplayLines = ambiguousDisplayLines;
            this.methods = methods;
        }

        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("className", target.getName());
            result.put("classLoaderHash", ClassTargetResolver.identityHash(target.getClassLoader()));
            result.put("classLoaderName", target.getClassLoader() == null
                    ? "bootstrap" : target.getClassLoader().getClass().getName());
            result.put("modifiable", Global.isModifiableClass(target));
            result.put("source", source);
            result.put("executableDisplayLines", executableDisplayLines());
            result.put("ambiguousDisplayLines", new ArrayList<>(ambiguousDisplayLines));
            result.put("capabilities", capabilities());
            List<Map<String, Object>> methodMaps = new ArrayList<>();
            for (MethodInfo method : methods) {
                methodMaps.add(method.toMap());
            }
            result.put("methods", methodMaps);
            return result;
        }

        BreakpointLocation locate(int displayLine, boolean adjustToNearest) {
            if (ambiguousDisplayLines.contains(displayLine)) {
                throw new IllegalArgumentException(
                        "Display line maps to more than one bytecode line or method: "
                                + displayLine);
            }
            for (MethodInfo method : methods) {
                if (!method.supported || method.displayedLines.isEmpty()) {
                    continue;
                }
                int index = method.displayedLines.indexOf(displayLine);
                if (index >= 0) {
                    return location(method, displayLine, displayLine);
                }
            }
            List<MethodInfo> containing = new ArrayList<>();
            for (MethodInfo method : methods) {
                if (method.supported && method.contains(displayLine)) {
                    containing.add(method);
                }
            }
            List<Integer> candidates = containing.size() == 1
                    ? nearestCandidates(displayLine, containing.get(0).displayedLines)
                    : nearestCandidates(displayLine, executableDisplayLines());
            if (!adjustToNearest || containing.size() != 1 || candidates.isEmpty()) {
                throw new IllegalArgumentException("Display line " + displayLine
                        + " is not executable in one supported method; nearby executable lines: "
                        + candidates);
            }
            int adjusted = candidates.get(0);
            return location(containing.get(0), displayLine, adjusted);
        }

        private BreakpointLocation location(MethodInfo method, int requested, int actualDisplay) {
            Integer original = displayToOriginal.get(actualDisplay);
            if (original == null || !method.originalLines.contains(original)) {
                throw new IllegalArgumentException("Display line has no bytecode line mapping: " + actualDisplay);
            }
            return new BreakpointLocation(method.name, method.descriptor, requested, actualDisplay,
                    original);
        }

        private List<Integer> nearestCandidates(int requested, List<Integer> source) {
            List<Integer> result = new ArrayList<>(source);
            result.sort((left, right) -> {
                int distance = Integer.compare(Math.abs(left - requested), Math.abs(right - requested));
                return distance == 0 ? Integer.compare(left, right) : distance;
            });
            return result.size() <= 5 ? result : new ArrayList<>(result.subList(0, 5));
        }

        private List<Integer> executableDisplayLines() {
            List<Integer> result = new ArrayList<>();
            for (MethodInfo method : methods) {
                if (method.supported) {
                    result.addAll(method.displayedLines);
                }
            }
            Collections.sort(result);
            return result;
        }

        private Map<String, Object> capabilities() {
            Map<String, Object> result = new LinkedHashMap<>();
            boolean lineNumbers = !executableDisplayLines().isEmpty();
            result.put("lineBreakpoints", lineNumbers);
            List<String> limitations = new ArrayList<>();
            if (!lineNumbers) {
                limitations.add("LineNumberTable or decompiler line mapping is unavailable");
            }
            if (!ambiguousDisplayLines.isEmpty()) {
                limitations.add("Ambiguous decompiler line mappings are not selectable");
            }
            limitations.add("Constructors and static initializers are not supported");
            result.put("limitations", limitations);
            return result;
        }
    }

    static final class BreakpointLocation {
        final String methodName;
        final String descriptor;
        final int requestedDisplayLine;
        final int actualDisplayLine;
        final int originalLine;

        BreakpointLocation(String methodName, String descriptor, int requestedDisplayLine,
                           int actualDisplayLine, int originalLine) {
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.requestedDisplayLine = requestedDisplayLine;
            this.actualDisplayLine = actualDisplayLine;
            this.originalLine = originalLine;
        }
    }

    private static final class MethodInfo {
        private final String name;
        private final String descriptor;
        private final List<Integer> originalLines;
        private final List<Integer> displayedLines;
        private final int displayStart;
        private final int displayEnd;
        private final boolean supported;

        private MethodInfo(String name, String descriptor, List<Integer> originalLines,
                           List<Integer> displayedLines, int displayStart, int displayEnd,
                           boolean supported) {
            this.name = name;
            this.descriptor = descriptor;
            this.originalLines = originalLines;
            this.displayedLines = displayedLines;
            this.displayStart = displayStart;
            this.displayEnd = displayEnd;
            this.supported = supported;
        }

        private boolean contains(int displayLine) {
            return displayStart > 0 && displayStart <= displayLine && displayLine <= displayEnd;
        }

        private int firstOriginalLine() {
            return originalLines.get(0);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name);
            result.put("descriptor", descriptor);
            result.put("executableOriginalLines", originalLines);
            result.put("executableDisplayLines", supported
                    ? displayedLines : Collections.emptyList());
            result.put("displayStartLine", displayStart);
            result.put("displayEndLine", displayEnd);
            result.put("supported", supported);
            result.put("unsupportedReason", supported ? null
                    : "Constructors and static initializers are not supported");
            return result;
        }
    }

    private static final class DecompileResult {
        private final String source;
        private final int[] mapping;

        private DecompileResult(String source, int[] mapping) {
            this.source = source;
            this.mapping = mapping;
        }
    }

    private static final class ResultSaver implements IResultSaver {
        private final String target;
        private String source;
        private int[] mapping;

        private ResultSaver(String target) {
            this.target = target;
        }

        @Override
        public void saveClassFile(String path, String qualifiedName, String entryName,
                                  String content, int[] mapping) {
            if (content != null && (entryName.equals(target.replace('.', '/') + ".java")
                    || qualifiedName.equals(target) || source == null)) {
                source = content;
                this.mapping = mapping == null ? null : mapping.clone();
            }
        }

        @Override public void saveFolder(String path) { }
        @Override public void copyFile(String source, String path, String entryName) { }
        @Override public void createArchive(String path, String archiveName, Manifest manifest) { }
        @Override public void saveDirEntry(String path, String archiveName, String entryName) { }
        @Override public void copyEntry(String source, String path, String archiveName, String entryName) { }
        @Override public void saveClassEntry(String path, String archiveName, String qualifiedName,
                                             String entryName, String content) { }
        @Override public void closeArchive(String path, String archiveName) { }
    }

    private static final class SilentLogger extends IFernflowerLogger {
        @Override public void writeMessage(String message, Severity severity) { }
        @Override public void writeMessage(String message, Severity severity, Throwable throwable) { }
    }
}
