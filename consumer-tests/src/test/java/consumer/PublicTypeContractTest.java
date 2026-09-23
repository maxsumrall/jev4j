package consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PublicTypeContractTest {
  @TempDir Path output;

  @Test
  void compilerEnforcesPlainNoulAndEnumTypeContracts() throws Exception {
    assertTrue(
        compiles(
            "import io.github.maxsumrall.jev4j.Jev; class T { boolean f() { return Jev.noul(\"x\").answer(.5).isTrueAt(.5); } }"));
    assertFalse(
        compiles(
            "import io.github.maxsumrall.jev4j.Jev; class T { boolean f() { return Jev.noul(\"x\").answer(.5).isTrue(); } }"));
    assertFalse(
        compiles(
            "import io.github.maxsumrall.jev4j.Jev; import java.util.Map; class T { enum A { X } enum B { X } void f() { Jev.choice(A.class, \"x\").answer(B.X, Map.of(A.X, 1.0), 1); } }"));
    assertTrue(
        compiles(
            "import io.github.maxsumrall.jev4j.Jev; import java.util.Map; class T { enum A { X } void f() { Jev.choice(A.class, \"x\").answer(A.X, Map.of(A.X, 1.0), 1); } }"));
  }

  private boolean compiles(String source) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    SimpleJavaFileObject unit =
        new SimpleJavaFileObject(
            URI.create("string:///T.java"), javax.tools.JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
          }
        };
    List<String> options =
        List.of(
            "--release",
            "17",
            "-classpath",
            System.getProperty("java.class.path"),
            "-d",
            output.toString());
    return compiler.getTask(null, null, diagnostic -> {}, options, null, List.of(unit)).call();
  }
}
