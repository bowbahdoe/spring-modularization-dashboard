package dev.mccue.spring.dashboard;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Main {
    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "demo";

        // Save dependency tree for later
        String depTree;
        {
            int exitCode = new ProcessBuilder(List.of(
                    "./mvnw", /* "-U", */ "dependency:tree", "-DoutputFile=tree.txt" //, "-DoutputType=dot"
            ))
                    .directory(new File(path))
                    .inheritIO()
                    .start()
                    .waitFor();

            if (exitCode != 0) {
                System.err.println("Error getting classpath");
                System.exit(exitCode);
            }
            depTree = Files.readString(Path.of(path + "/tree.txt"));
        }

        String verboseDepTree;
        {
            int exitCode = new ProcessBuilder(List.of(
                    "./mvnw", "-Dverbose=true", "dependency:tree", "-DoutputFile=verbose-tree.txt" //, "-DoutputType=dot"
            ))
                    .directory(new File(path))
                    .inheritIO()
                    .start()
                    .waitFor();

            if (exitCode != 0) {
                System.err.println("Error getting classpath");
                System.exit(exitCode);
            }
            verboseDepTree = Files.readString(Path.of(path + "/verbose-tree.txt"));
        }

        // Get the classpath for everything
        {
            int exitCode = new ProcessBuilder(List.of(
                    "./mvnw", /* "-U", */ "dependency:build-classpath", "-Dmdep.outputFile=cp.txt"
            ))
                    .directory(new File(path))
                    .inheritIO()
                    .start()
                    .waitFor();

            if (exitCode != 0) {
                System.err.println("Error getting classpath");
                System.exit(exitCode);
            }
        }

        List<String> classPathEntries = Arrays.asList(
                Files.readString(Path.of(path + "/cp.txt"))
                        .split(File.pathSeparator)
        );

        System.out.println(classPathEntries);

        enum ModuleInfoStatus {
            NO_MODULE_INFO,
            AUTOMATIC_MODULE_NAME,
            FULL_MODULE_INFO
        }

        record Dependency(
                String path,
                String groupId,
                String artifactId,
                String version,
                String fileName,
                ModuleInfoStatus moduleInfoStatus
        ) {
            Dependency withModuleInfoStatus(ModuleInfoStatus moduleInfoStatus) {
                return new Dependency(
                        path, groupId, artifactId, version, fileName, moduleInfoStatus
                );
            }
        }

        var dependencies = new ArrayList<Dependency>();
        for (var classPathEntry : classPathEntries) {
            String[] pathComponents = classPathEntry.split("/");
            var fileName = pathComponents[pathComponents.length - 1];
            var version = pathComponents[pathComponents.length - 2];
            var artifactId = pathComponents[pathComponents.length - 3];
            var m2Idx = Arrays.asList(pathComponents).indexOf(".m2");
            var groupId = String.join(".", Arrays.asList(pathComponents).subList(m2Idx + 2, pathComponents.length - 3));
            dependencies.add(new Dependency(
                    classPathEntry,
                    groupId,
                    artifactId,
                    version,
                    fileName,
                    null
            ));
        }


        System.out.println("Checking for module infos");
        {
            for (int i = 0; i < dependencies.size(); i++) {
                var dependency = dependencies.get(i);
                var uri = URI.create("jar:file:" + dependency.path);
                try (var fs = FileSystems.newFileSystem(uri, Map.of())) {
                    var paths = new ArrayList<Path>();
                    paths.add(fs.getPath("/module-info.class"));
                    int version = Runtime.version().feature();
                    while (version-- > 9) {
                        paths.add(fs.getPath("/META-INF/versions/%d/module-info.class".formatted(version)));
                    }

                    boolean foundModuleInfo = false;
                    for (var p : paths) {
                        if (Files.exists(p)) {
                            dependencies.set(i, dependency.withModuleInfoStatus(ModuleInfoStatus.FULL_MODULE_INFO));
                            foundModuleInfo = true;
                            break;
                        }
                    }

                    boolean foundAutomaticModule = false;
                    if (!foundModuleInfo) {
                        try {
                            var manifest = Files.readAllBytes(fs.getPath("/META-INF/MANIFEST.MF"));
                            var properties = new Properties();
                            properties.load(new InputStreamReader(new ByteArrayInputStream(manifest)));

                            if (properties.get("Automatic-Module-Name") != null) {
                                foundAutomaticModule = true;
                                dependencies.set(i, dependency.withModuleInfoStatus(ModuleInfoStatus.AUTOMATIC_MODULE_NAME));
                            }
                        } catch (NoSuchFileException e) {
                            // NoOp
                        }
                    }

                    if (!foundModuleInfo && !foundAutomaticModule) {
                        dependencies.set(i, dependency.withModuleInfoStatus(ModuleInfoStatus.NO_MODULE_INFO));
                    }
                }
            }
        }

        var grouped = dependencies.stream()
                .collect(Collectors.groupingBy(Dependency::moduleInfoStatus));
        grouped.forEach((status, deps) -> {
            System.out.println(status + ": " + deps.size());
        });
        System.out.println("Total: " + dependencies.size());


        var html = new StringBuilder();
        html.append("<html><body style=\"background-color:#2e3440;\">");

        html.append("<span style=\"color:#ECEFF4\">");
        html.append("""
                <h1> Spring Modularization Dashboard </h1>
                                
                <h2 style="color:#BF616A"> No module info: %d (%s%%) </h2>
                <h2 style="color:#EBCB8B"> Automatic module name: %d (%s%%) </h2>
                <h2 style="color:#A3BE8C"> Full module info: %d (%s%%) </h2>
                                
                <p> This page lists the libraries you would get if you went to
                <a style="color:inherit" href="https://start.spring.io">https://start.spring.io</a>
                and selected every option. </p>
                                
                <p> Given the widespread popularity of Spring, making most everything in this an explicit module
                is probably a prerequisite for more widespread module system adoption.</p>
                
                <p> This is not updated automatically, but that should be relatively easy to do. The GitHub repo is
                <a style="color:inherit" href="https://github.com/bowbahdoe/spring-modularization-dashboard">here</a>
                if you have the time and inclination.
                </p>
                """.formatted(
                grouped.get(ModuleInfoStatus.NO_MODULE_INFO).size(),
                Math.round((grouped.get(ModuleInfoStatus.NO_MODULE_INFO).size() / (double) dependencies.size()) * 100),
                grouped.get(ModuleInfoStatus.AUTOMATIC_MODULE_NAME).size(),
                Math.round((grouped.get(ModuleInfoStatus.AUTOMATIC_MODULE_NAME).size() / (double) dependencies.size()) * 100),
                grouped.get(ModuleInfoStatus.FULL_MODULE_INFO).size(),
                Math.round((grouped.get(ModuleInfoStatus.FULL_MODULE_INFO).size() / (double) dependencies.size()) * 100)
        ));
        BiFunction<String, String, String> link = (text, href) -> "<a style=\"color:inherit\" href=\"" + href + "\">" + text + "</a>";
        Function<String, String> red = s -> "<span style=\"color:#BF616A\">" + s + "</span>";
        Function<String, String> yellow = s -> "<span style=\"color:#EBCB8B\">" + s + "</span>";
        Function<String, String> green = s -> "<span style=\"color:#A3BE8C\">" + s + "</span>";

        depTree = depTree.replace("com.example:demo:jar:0.0.1-SNAPSHOT", "");
        verboseDepTree = verboseDepTree.replace("com.example:demo:jar:0.0.1-SNAPSHOT", "");
        verboseDepTree = verboseDepTree.replace("- (", "- ");
        verboseDepTree = verboseDepTree.replaceAll(" - version managed(.+)\n", "\n");
        verboseDepTree = verboseDepTree.replaceAll(" - omitted for conflict(.+)\n", "\n");

        for (var dependency : dependencies) {
            var color = switch (dependency.moduleInfoStatus) {
                case NO_MODULE_INFO -> red;
                case AUTOMATIC_MODULE_NAME -> yellow;
                case FULL_MODULE_INFO -> green;
            };

            Function<String, String> processTree = tree -> tree.replaceAll(
                    dependency.groupId + ":" + dependency.artifactId + ":(.+):" + dependency.version + "(.+)\n",
                    color.apply(
                            link.apply(
                                    dependency.groupId + "/" + dependency.artifactId + "@" + dependency.version + "\n",
                                    "https://central.sonatype.com/artifact/" + dependency.groupId + "/" + dependency.artifactId
                            )
                    )
            );

            depTree = processTree.apply(depTree);
            verboseDepTree = processTree.apply(verboseDepTree);
        }

        html.append("""
                <script>
                   var verbose = false;
                   function toggleVerbose() {
                      if (verbose) {
                          document.getElementById("tree").style = "";
                          document.getElementById("verbose-tree").style = "display:none";
                          document.getElementById("verbose-button").innerText = "Toggle Verbose On";
                          verbose = false;
                      }
                      else {
                          document.getElementById("tree").style = "display:none";
                          document.getElementById("verbose-tree").style = "";
                          document.getElementById("verbose-button").innerText = "Toggle Verbose Off";
                          verbose = true;
                      }
                   };
                </script>
                <button id="verbose-button" onclick="toggleVerbose()"> Toggle Verbose On </button>
                """);
        html.append("<hr>");
        html.append("<pre id=\"tree\">");
        html.append(depTree);
        html.append("</pre>");
        html.append("<pre style=\"display:none\" id=\"verbose-tree\">");
        html.append(verboseDepTree);
        html.append("</pre>");
        html.append("</span>");

        html.append("</body></html>");

        System.out.println(depTree);

        Files.createDirectories(Path.of("site"));
        Files.writeString(Path.of("site/index.html"), html.toString());
    }
}
