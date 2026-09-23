package consumer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.maxsumrall.jev4j.Jev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.tools.ToolProvider;

final class ReadmeCompileTest {
    @TempDir Path output;

    @Test
    void publishedJavaFragmentsCompileAgainstInstalledJar() throws Exception {
        Path readme = Path.of("../README.md"); // Surefire's working directory is consumer-tests.
        List<String> lines = Files.readAllLines(readme, StandardCharsets.UTF_8);
        Set<String> imports = new LinkedHashSet<>();
        StringBuilder members = new StringBuilder();
        StringBuilder body = new StringBuilder();
        int fragments = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).strip().equals("```java")) {
                continue;
            }
            String marker = i == 0 ? "" : lines.get(i - 1).strip();
            assertTrue(
                    marker.equals("<!-- java: members -->") || marker.equals("<!-- java: body -->"),
                    "README.md:" + (i + 1) + " needs a java: members or java: body marker");
            StringBuilder target = marker.equals("<!-- java: members -->") ? members : body;
            fragments++;
            for (i++; i < lines.size() && !lines.get(i).strip().equals("```"); i++) {
                String line = lines.get(i);
                if (line.startsWith("import ")) {
                    imports.add(line);
                } else {
                    target.append("// README.md:")
                            .append(i + 1)
                            .append('\n')
                            .append(line)
                            .append('\n');
                }
            }
            assertTrue(i < lines.size(), "Unclosed Java fence in README.md");
        }
        assertTrue(fragments > 0, "No Java fences found in README.md");

        // Only syntax scaffolding: API calls, imports, and scenario context come from the README.
        Path source = output.resolve("ReadmeScenario.java");
        String sourceText =
                String.join("\n", imports)
                        + "\nclass ReadmeScenario {\n"
                        + members
                        + "\nvoid scenario() {\n"
                        + body
                        + "\n}\n}\n";
        Files.writeString(source, sourceText, StandardCharsets.UTF_8);
        Path jar = Path.of(Jev.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(
                Files.isRegularFile(jar) && jar.toString().endsWith(".jar"),
                "Expected installed JAR");
        javax.tools.JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Run consumer-tests with a JDK");
        StringWriter diagnostics = new StringWriter();
        try (javax.tools.StandardJavaFileManager files =
                compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            boolean compiled =
                    compiler.getTask(
                                    diagnostics,
                                    files,
                                    null,
                                    List.of(
                                            "--release",
                                            "17",
                                            "-proc:none",
                                            "-classpath",
                                            jar.toString(),
                                            "-d",
                                            output.toString()),
                                    null,
                                    files.getJavaFileObjects(source))
                            .call();
            assertTrue(
                    compiled,
                    () -> "README Java compilation failed:\n" + diagnostics + "\n" + sourceText);
        }
    }
}
