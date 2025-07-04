package org.smethods;

import org.objectweb.asm.ClassReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class MethodLevelStaticDepsBuilder {
    // mvn exec:java -Dexec.mainClass=org.smethods.MethodLevelStaticDepsBuilder
    // -Dexec.args="path to test project"

    // for every class, get the methods it implements
    public static Map<String, Set<String>> class2ContainedMethodNames = new HashMap<>();
    // for every method, get the methods it invokes
    public static Map<String, Set<String>> methodName2MethodNames = new HashMap<>();
    // for every class, find its parents.
    public static Map<String, Set<String>> hierarchy_parents = new HashMap<>();
    // for every class, find its children.
    public static Map<String, Set<String>> hierarchy_children = new HashMap<>();

    public static void main(String... args) throws Exception {
        // We need at least the argument that points to the root
        // directory where the search for .class files will start.
        if (args.length < 1) {
            throw new RuntimeException("Incorrect arguments");
        }
        String pathToStartDir = args[0];
        HashSet classPaths = new HashSet<>(Files.walk(Paths.get(pathToStartDir))
                .filter(Files::isRegularFile).filter(f -> (f.toString().endsWith(".class"))) // &&
                                                                                             // f.toString().contains("target")
                .map(f -> f.normalize().toAbsolutePath().toString()).collect(Collectors.toList()));

        // find the methods that each method calls
        findMethodsinvoked(classPaths);

        // suppose that test classes have Test in their class name
        // Set<String> testClasses = new HashSet<>();
        // for (String method : methodName2MethodNames.keySet()) {
        // String className = method.split("#|\\$")[0];
        // if (className.contains("Test")) {
        // testClasses.add(className);
        // }
        // }

        // Map<String, Set<String>> test2methods = getDepsSingleThread(testClasses);

        // create Macros.STARTS_ROOT_DIR_NAME folder if not exist
        if (!Files.exists(Paths.get(Macros.SMETHODS_ROOT_DIR_NAME))) {
            Files.createDirectory(Paths.get(Macros.SMETHODS_ROOT_DIR_NAME));
        }
        // save debugging info
        FileUtil.saveMap(methodName2MethodNames, Macros.SMETHODS_ROOT_DIR_NAME,
                "graph.txt");
        // FileUtil.saveMap(hierarchy_parents, Macros.SMETHODS_ROOT_DIR_NAME,
        // "hierarchy_parents.txt");
        // FileUtil.saveMap(hierarchy_children, Macros.SMETHODS_ROOT_DIR_NAME,
        // "hierarchy_children.txt");
        // FileUtil.saveMap(class2ContainedMethodNames, Macros.SMETHODS_ROOT_DIR_NAME,
        // "class2methods.txt");
        // FileUtil.saveMap(test2methods, Macros.SMETHODS_ROOT_DIR_NAME, "test2methods.txt");
    }

    public static void findMethodsinvoked(Set<String> classPaths) {
        for (String classPath : classPaths) {
            try {
                ClassReader classReader = new ClassReader(new FileInputStream(new File(classPath)));
                ClassToMethodsCollectorCV classToMethodsVisitor = new ClassToMethodsCollectorCV(
                        class2ContainedMethodNames, hierarchy_parents, hierarchy_children);
                classReader.accept(classToMethodsVisitor, ClassReader.SKIP_DEBUG);
            } catch (IOException e) {
                System.out.println("Cannot parse file: " + classPath);
                continue;
            }
        }

        for (String classPath : classPaths) {
            try {
                ClassReader classReader = new ClassReader(new FileInputStream(new File(classPath)));
                MethodCallCollectorCV methodClassVisitor =
                        new MethodCallCollectorCV(methodName2MethodNames, hierarchy_parents,
                                hierarchy_children, class2ContainedMethodNames);
                classReader.accept(methodClassVisitor, ClassReader.SKIP_DEBUG);
            } catch (IOException e) {
                System.out.println("Cannot parse file: " + classPath);
                continue;
            }
        }

        // deal with test class in a special way, all the @test method in hierarchy
        // should be considered
        for (String superClass : hierarchy_children.keySet()) {
            if (superClass.contains("Test")) {
                for (String subClass : hierarchy_children.getOrDefault(superClass,
                        new HashSet<>())) {
                    for (String methodSig : class2ContainedMethodNames.getOrDefault(superClass,
                            new HashSet<>())) {
                        String subClassKey = subClass + "#" + methodSig;
                        String superClassKey = superClass + "#" + methodSig;
                        methodName2MethodNames.computeIfAbsent(subClassKey, k -> new TreeSet<>())
                                .add(superClassKey);
                    }
                }
            }
        }
    }

    public static Set<String> getDepsHelper(String testClass) {
        Set<String> visitedMethods = new TreeSet<>();
        // BFS
        ArrayDeque<String> queue = new ArrayDeque<>();

        // initialization
        for (String method : methodName2MethodNames.keySet()) {
            if (method.startsWith(testClass + "#")) {
                queue.add(method);
                visitedMethods.add(method);
            }
        }

        while (!queue.isEmpty()) {
            String currentMethod = queue.pollFirst();
            for (String invokedMethod : methodName2MethodNames.getOrDefault(currentMethod,
                    new HashSet<>())) {
                if (!visitedMethods.contains(invokedMethod)) {
                    queue.add(invokedMethod);
                    visitedMethods.add(invokedMethod);
                }
            }
        }
        return visitedMethods;
    }

    // simple DFS
    public static void getDepsDFS(String methodName, Set<String> visitedMethods) {
        if (methodName2MethodNames.containsKey(methodName)) {
            for (String method : methodName2MethodNames.get(methodName)) {
                if (!visitedMethods.contains(method)) {
                    visitedMethods.add(method);
                    getDepsDFS(method, visitedMethods);
                }
            }
        }
    }

    public static Set<String> getDeps(String testClass) {
        Set<String> visited = new HashSet<>();
        for (String method : methodName2MethodNames.keySet()) {
            if (method.startsWith(testClass + "#")) {
                visited.add(method);
                getDepsDFS(method, visited);
            }
        }
        return visited;
    }

    public static Map<String, Set<String>> getDepsSingleThread(Set<String> testClasses) {
        Map<String, Set<String>> test2methods = new HashMap<>();
        for (String testClass : testClasses) {
            test2methods.put(testClass, getDeps(testClass));
        }
        return test2methods;
    }

    public static Map<String, Set<String>> getDepsMultiThread(Set<String> testClasses) {
        Map<String, Set<String>> test2methods = new ConcurrentSkipListMap<>();
        ExecutorService service = null;
        try {
            service = Executors.newFixedThreadPool(16);
            for (final String testClass : testClasses) {
                service.submit(() -> {
                    Set<String> invokedMethods = getDeps(testClass);
                    test2methods.put(testClass, invokedMethods);
                    // numMethodDepNodes.addAll(invokedMethods);
                });
            }
            service.shutdown();
            service.awaitTermination(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return test2methods;
    }

    public static Set<String> getMethodsFromHierarchies(String currentMethod,
            Map<String, Set<String>> hierarchies) {
        Set<String> res = new HashSet<>();
        // consider the superclass/subclass, do not have to consider the constructors
        String currentMethodSig = currentMethod.split("#")[1];
        if (!currentMethodSig.startsWith("<init>") && !currentMethodSig.startsWith("<clinit>")) {
            String currentClass = currentMethod.split("#")[0];
            for (String hClass : hierarchies.getOrDefault(currentClass, new HashSet<>())) {
                String hMethod = hClass + "#" + currentMethodSig;
                res.addAll(getMethodsFromHierarchies(hMethod, hierarchies));
                res.add(hMethod);
            }
        }
        return res;
    }

}
