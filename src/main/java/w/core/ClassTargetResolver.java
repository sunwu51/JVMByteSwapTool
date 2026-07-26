package w.core;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves loaded classes by exact ClassLoader identity.
 */
public class ClassTargetResolver {
    private final Instrumentation instrumentation;

    public ClassTargetResolver(Instrumentation instrumentation) {
        if (instrumentation == null) {
            throw new IllegalArgumentException("instrumentation is required");
        }
        this.instrumentation = instrumentation;
    }

    public List<ClassTarget> list(String className) {
        List<ClassTarget> result = new ArrayList<>();
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (loaded != null && loaded.getName().equals(className)) {
                result.add(new ClassTarget(loaded, instrumentation.isModifiableClass(loaded)));
            }
        }
        return result;
    }

    public List<ClassLoaderTarget> listClassLoaders() {
        Map<ClassLoader, ClassLoaderTarget> targets = new IdentityHashMap<>();
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (loaded == null) {
                continue;
            }
            ClassLoader loader = loaded.getClassLoader();
            ClassLoaderTarget target = targets.computeIfAbsent(loader, ClassLoaderTarget::new);
            target.classCount++;
            if (instrumentation.isModifiableClass(loaded)) {
                target.modifiableClassCount++;
            }
        }
        List<ClassLoaderTarget> result = new ArrayList<>(targets.values());
        result.sort(Comparator.comparing(ClassLoaderTarget::getClassLoaderName)
                .thenComparing(ClassLoaderTarget::getClassLoaderHash));
        return result;
    }

    public Resolution resolve(String className, String requestedHash) {
        List<ClassTarget> candidates = list(className);
        if (candidates.isEmpty()) {
            return Resolution.failure("Class not loaded: " + className, candidates);
        }
        Map<String, Set<ClassLoader>> loadersByHash = new LinkedHashMap<>();
        for (ClassTarget candidate : candidates) {
            loadersByHash.computeIfAbsent(candidate.getClassLoaderHash(),
                    ignored -> Collections.newSetFromMap(new IdentityHashMap<>()))
                    .add(candidate.getType().getClassLoader());
        }
        for (Map.Entry<String, Set<ClassLoader>> entry : loadersByHash.entrySet()) {
            if (entry.getValue().size() > 1) {
                return Resolution.failure("ClassLoader identity hash collision for " + className
                        + ": " + entry.getKey(), candidates);
            }
        }

        String normalizedHash = normalizeHash(requestedHash);
        if (normalizedHash == null) {
            if (loadersByHash.size() != 1 || candidates.size() != 1) {
                return Resolution.failure("Multiple loaded classes named " + className
                        + "; classLoaderHash is required" + describeCandidates(candidates), candidates);
            }
            return Resolution.success(candidates.get(0), candidates);
        }

        ClassTarget match = null;
        for (ClassTarget candidate : candidates) {
            if (candidate.getClassLoaderHash().equals(normalizedHash)) {
                if (match != null && match.getType() != candidate.getType()) {
                    return Resolution.failure("ClassLoader hash does not uniquely identify " + className
                            + ": " + normalizedHash, candidates);
                }
                match = candidate;
            }
        }
        return match == null
                ? Resolution.failure("No loaded " + className + " for classLoaderHash " + normalizedHash, candidates)
                : Resolution.success(match, candidates);
    }

    private String describeCandidates(List<ClassTarget> candidates) {
        StringBuilder result = new StringBuilder("\nCandidates:");
        for (ClassTarget candidate : candidates) {
            ClassLoader loader = candidate.getType().getClassLoader();
            result.append("\n- ")
                    .append(candidate.getClassLoaderHash())
                    .append(": ")
                    .append(loader == null ? "bootstrap" : loader.toString());
        }
        return result.toString();
    }

    private String normalizeHash(String hash) {
        if (hash == null || hash.trim().isEmpty()) {
            return null;
        }
        String value = hash.trim().toLowerCase();
        return value.startsWith("0x") ? value.substring(2) : value;
    }

    public static String identityHash(ClassLoader loader) {
        return Integer.toHexString(System.identityHashCode(loader));
    }

    public static final class ClassTarget {
        private final Class<?> type;
        private final boolean modifiable;

        ClassTarget(Class<?> type, boolean modifiable) {
            this.type = type;
            this.modifiable = modifiable;
        }

        public Class<?> getType() {
            return type;
        }

        public String getClassLoaderHash() {
            return identityHash(type.getClassLoader());
        }

        public Map<String, Object> toMap() {
            ClassLoader loader = type.getClassLoader();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("className", type.getName());
            item.put("classLoaderHash", getClassLoaderHash());
            item.put("classLoaderName", loader == null ? "bootstrap" : loader.toString());
            ClassLoader parent = loader == null ? null : loader.getParent();
            item.put("parentClassLoader", loader == null ? "none"
                    : (parent == null ? "bootstrap" : parent.toString()));
            item.put("codeSource", codeSource(type));
            item.put("modifiable", modifiable);
            return item;
        }

        private static String codeSource(Class<?> type) {
            try {
                CodeSource source = type.getProtectionDomain().getCodeSource();
                URL location = source == null ? null : source.getLocation();
                return location == null ? "unknown" : location.toExternalForm();
            } catch (Throwable ignored) {
                return "unknown";
            }
        }
    }

    public static final class ClassLoaderTarget {
        private final ClassLoader loader;
        private int classCount;
        private int modifiableClassCount;

        private ClassLoaderTarget(ClassLoader loader) {
            this.loader = loader;
        }

        public String getClassLoaderHash() {
            return identityHash(loader);
        }

        public String getClassLoaderName() {
            return loader == null ? "bootstrap" : loader.toString();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("classLoaderHash", getClassLoaderHash());
            item.put("classLoaderName", getClassLoaderName());
            ClassLoader parent = loader == null ? null : loader.getParent();
            item.put("parentClassLoader", loader == null ? "none"
                    : (parent == null ? "bootstrap" : parent.toString()));
            item.put("classCount", classCount);
            item.put("modifiableClassCount", modifiableClassCount);
            return item;
        }
    }

    public static final class Resolution {
        private final ClassTarget target;
        private final String error;
        private final List<ClassTarget> candidates;

        private Resolution(ClassTarget target, String error, List<ClassTarget> candidates) {
            this.target = target;
            this.error = error;
            this.candidates = candidates;
        }

        static Resolution success(ClassTarget target, List<ClassTarget> candidates) {
            return new Resolution(target, null, candidates);
        }

        static Resolution failure(String error, List<ClassTarget> candidates) {
            return new Resolution(null, error, candidates);
        }

        public boolean isSuccess() {
            return target != null;
        }

        public ClassTarget getTarget() {
            return target;
        }

        public String getError() {
            return error;
        }

        public boolean hasCandidates() {
            return !candidates.isEmpty();
        }

        public List<Map<String, Object>> candidateMaps() {
            List<Map<String, Object>> result = new ArrayList<>();
            for (ClassTarget candidate : candidates) {
                result.add(candidate.toMap());
            }
            return result;
        }
    }
}
