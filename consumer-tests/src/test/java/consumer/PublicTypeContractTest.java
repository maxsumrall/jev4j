package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.maxsumrall.jev4j.Jev;
import io.github.maxsumrall.jev4j.JevEvaluator;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PublicTypeContractTest {
  @TempDir Path output;

  @Test
  void compilerEnforcesNoulAndEnumTypeContracts() throws Exception {
    assertTrue(
        compiles(
            "import io.github.maxsumrall.jev4j.Jev; class T { boolean f() { return Jev.noul(\"x\").answer(.5).isTrue(); } }"));
    assertFalse(
        compiles(
            "import io.github.maxsumrall.jev4j.Jev; import java.util.Map; class T { enum A { X } enum B { X } void f() { Jev.choice(A.class, \"x\").answer(B.X, Map.of(A.X, 1.0), 1); } }"));
    assertTrue(
        compiles(
            "import io.github.maxsumrall.jev4j.Jev; import java.util.Map; class T { enum A { X } void f() { Jev.choice(A.class, \"x\").answer(A.X, Map.of(A.X, 1.0), 1); } }"));
    assertTrue(
        compiles(
            "import io.github.maxsumrall.jev4j.*; class T { void f(JevEvaluator e) { e.evaluate(\"state\", Jev.noul(\"question\")); e.evaluateWithMetadata(\"state\", Jev.noul(\"question\")); } }"));
    assertFalse(
        compiles(
            "import io.github.maxsumrall.jev4j.*; class T { void f(JevEvaluator e) { e.evaluate(Jev.noul(\"question\"), \"state\"); } }"));
    assertTrue(
        compiles(
            "import io.github.maxsumrall.jev4j.*; class T { record D(boolean x, double y) {} D f(JevEvaluator e) { return e.evaluate(\"s\", Jev.noul(\"x\"), Jev.score(\"y\").level(\"a\").level(\"b\").build()).map((Jev.NoulAnswer x, Jev.ScoreAnswer y) -> new D(x.isTrue(), y.value())); } }"));
    assertFalse(
        compiles(
            "import io.github.maxsumrall.jev4j.*; class T { void f(JevEvaluator e) { JevEvaluator.Evaluation2<Jev.ScoreAnswer,Jev.NoulAnswer> x = e.evaluate(\"s\", Jev.noul(\"x\"), Jev.score(\"y\").level(\"a\").level(\"b\").build()); } }"));
    assertFalse(
        compiles(
            "import io.github.maxsumrall.jev4j.*; class T { void f(JevEvaluator e) { e.evaluate(\"s\", Jev.noul(\"x\"), Jev.score(\"y\").level(\"a\").level(\"b\").build()).map((Jev.ScoreAnswer x, Jev.NoulAnswer y) -> x.value()); } }"));
  }

  @Test
  void bothStateFormsPreserveAllSingleAndMultiAnswerTypes() {
    for (String inputType : List.of("String", "Jev.State")) {
      StringBuilder body =
          new StringBuilder(
              """
          Jev.NoulQuestion n = Jev.noul("n");
          Jev.ChoiceQuestion<Route> c = Jev.choice(Route.class, "c");
          Jev.EnumScoreQuestion<Route> es = Jev.score(Route.class, "es");
          Jev.ScoreQuestion s = Jev.score("s").level("low").level("high").build();
          boolean b = e.test(input, n);
          Jev.NoulAnswer na = e.evaluate(input, n);
          Jev.ChoiceAnswer<Route> ca = e.evaluate(input, c);
          Jev.EnumScoreAnswer<Route> ea = e.evaluate(input, es);
          Jev.ScoreAnswer sa = e.evaluate(input, s);
          JevEvaluator.Evaluation<Jev.NoulAnswer> nm = e.evaluateWithMetadata(input, n);
          JevEvaluator.Evaluation<Jev.ChoiceAnswer<Route>> cm = e.evaluateWithMetadata(input, c);
          JevEvaluator.Evaluation<Jev.EnumScoreAnswer<Route>> em = e.evaluateWithMetadata(input, es);
          JevEvaluator.Evaluation<Jev.ScoreAnswer> sm = e.evaluateWithMetadata(input, s);
          CompletableFuture<Boolean> ab = e.testAsync(input, n);
          CompletableFuture<Jev.NoulAnswer> an = e.evaluateAsync(input, n);
          CompletableFuture<Jev.ChoiceAnswer<Route>> ac = e.evaluateAsync(input, c);
          CompletableFuture<Jev.EnumScoreAnswer<Route>> ae = e.evaluateAsync(input, es);
          CompletableFuture<Jev.ScoreAnswer> as = e.evaluateAsync(input, s);
          CompletableFuture<JevEvaluator.Evaluation<Jev.NoulAnswer>> amn = e.evaluateWithMetadataAsync(input, n);
          CompletableFuture<JevEvaluator.Evaluation<Jev.ChoiceAnswer<Route>>> amc = e.evaluateWithMetadataAsync(input, c);
          CompletableFuture<JevEvaluator.Evaluation<Jev.EnumScoreAnswer<Route>>> ame = e.evaluateWithMetadataAsync(input, es);
          CompletableFuture<JevEvaluator.Evaluation<Jev.ScoreAnswer>> ams = e.evaluateWithMetadataAsync(input, s);
          Jev.Question<Jev.NoulAnswer> generic = n;
          Jev.NoulAnswer genericAnswer = e.evaluate(input, generic);
          JevEvaluator.Evaluation<Jev.NoulAnswer> genericMetadata = e.evaluateWithMetadata(input, generic);
          CompletableFuture<Jev.NoulAnswer> ag = e.evaluateAsync(input, generic);
          """);
      for (int arity = 2; arity <= 8; arity++) {
        List<String> types = new ArrayList<>();
        List<String> questions = new ArrayList<>();
        List<String> parameters = new ArrayList<>();
        for (int i = 1; i <= arity; i++) {
          String type = i % 2 == 1 ? "Jev.NoulAnswer" : "Jev.ChoiceAnswer<Route>";
          types.add(type);
          questions.add(i % 2 == 1 ? "n" : "c");
          parameters.add(type + " a" + i);
        }
        body.append("JevEvaluator.Evaluation")
            .append(arity)
            .append('<')
            .append(String.join(",", types))
            .append("> r")
            .append(arity)
            .append(" = e.evaluate(input,")
            .append(String.join(",", questions))
            .append(");")
            .append("boolean mapped")
            .append(arity)
            .append(" = r")
            .append(arity)
            .append(".map((")
            .append(String.join(",", parameters))
            .append(") -> a1.isTrue());");
        body.append("CompletableFuture<JevEvaluator.Evaluation")
            .append(arity)
            .append('<')
            .append(String.join(",", types))
            .append(">> ar")
            .append(arity)
            .append(" = e.evaluateAsync(input,")
            .append(String.join(",", questions))
            .append(");")
            .append("CompletableFuture<Boolean> mappedAsync")
            .append(arity)
            .append(" = ar")
            .append(arity)
            .append(".thenApply(r -> r.map((")
            .append(String.join(",", parameters))
            .append(") -> a1.isTrue()));");
      }
      assertTrue(
          compiles(
              "import io.github.maxsumrall.jev4j.*; import java.util.concurrent.CompletableFuture; class T { enum Route { A, B } void f(JevEvaluator e, "
                  + inputType
                  + " input) {"
                  + body
                  + "} }"));
    }
    assertTrue(
        compiles(
            """
        import io.github.maxsumrall.jev4j.*;
        import java.util.*;
        class T {
          public record Ticket(String message, int attempts) {}
          void f(JevEvaluator e) {
            Jev.Question<Jev.NoulAnswer> question = Jev.noul("x");
            Jev.State input = Jev.State.from(new Ticket("help", 2));
            Jev.NoulAnswer answer = e.evaluate(input, question);
            e.evaluate(Jev.State.from(Map.of("attempts",2)), question);
            e.evaluate(Jev.State.from(new int[] {2,5}), question);
            e.evaluate(Jev.State.fromJson("{}"), question);
          }
        }
        """));
    assertFalse(
        compiles(
            "import io.github.maxsumrall.jev4j.*; class T { void f(JevEvaluator e, Jev.State s) { JevEvaluator.Evaluation2<Jev.ScoreAnswer,Jev.NoulAnswer> r = e.evaluate(s, Jev.noul(\"x\"), Jev.score(\"s\").level(\"a\").level(\"b\").build()); } }"));
    assertFalse(
        compiles(
            "import io.github.maxsumrall.jev4j.*; class T { void f(JevEvaluator e, Jev.State s) { e.evaluate(s, Jev.noul(\"x\"), Jev.score(\"s\").level(\"a\").level(\"b\").build()).map((Jev.ScoreAnswer a, Jev.NoulAnswer b) -> a.value()); } }"));
  }

  @Test
  void asyncRejectsWrongEnumAndTupleTypesForBothStateForms() {
    for (String inputType : List.of("String", "Jev.State")) {
      String prefix =
          "import io.github.maxsumrall.jev4j.*; import java.util.concurrent.CompletableFuture; class T { enum A { X } enum B { X } void f(JevEvaluator e, "
              + inputType
              + " s) {";
      assertFalse(
          compiles(
              prefix
                  + "CompletableFuture<Jev.ChoiceAnswer<B>> r = e.evaluateAsync(s, Jev.choice(A.class, \"x\")); } }"));
      assertFalse(
          compiles(
              prefix
                  + "CompletableFuture<JevEvaluator.Evaluation<Jev.EnumScoreAnswer<B>>> r = e.evaluateWithMetadataAsync(s, Jev.score(A.class, \"x\")); } }"));
      assertFalse(
          compiles(
              prefix
                  + "CompletableFuture<JevEvaluator.Evaluation2<Jev.ScoreAnswer,Jev.NoulAnswer>> r = e.evaluateAsync(s, Jev.noul(\"x\"), Jev.score(\"y\").level(\"a\").level(\"b\").build()); } }"));
      assertFalse(
          compiles(
              prefix
                  + "e.evaluateAsync(s, Jev.noul(\"x\"), Jev.choice(A.class, \"y\")).thenApply(r -> r.map((Jev.ChoiceAnswer<A> a, Jev.NoulAnswer b) -> b.isTrue())); } }"));
    }
  }

  @Test
  void typedNullCompilesButBareNullStateIsAmbiguous() {
    assertTrue(
        compiles(
            "import io.github.maxsumrall.jev4j.*; class T { void f(JevEvaluator e) { e.test((String)null, Jev.noul(\"x\")); e.test((Jev.State)null, Jev.noul(\"x\")); } }"));
    assertFalse(
        compiles(
            "import io.github.maxsumrall.jev4j.*; class T { void f(JevEvaluator e) { e.test(null, Jev.noul(\"x\")); } }"));
  }

  @Test
  void oldStringMethodDescriptorsRemainAvailable() throws Exception {
    Map<Class<?>, Class<?>> answers =
        Map.of(
            Jev.NoulQuestion.class, Jev.NoulAnswer.class,
            Jev.ChoiceQuestion.class, Jev.ChoiceAnswer.class,
            Jev.EnumScoreQuestion.class, Jev.EnumScoreAnswer.class,
            Jev.ScoreQuestion.class, Jev.ScoreAnswer.class);
    for (Map.Entry<Class<?>, Class<?>> entry : answers.entrySet()) {
      assertEquals(
          entry.getValue(),
          JevEvaluator.class.getMethod("evaluate", String.class, entry.getKey()).getReturnType());
      assertEquals(
          JevEvaluator.Evaluation.class,
          JevEvaluator.class
              .getMethod("evaluateWithMetadata", String.class, entry.getKey())
              .getReturnType());
    }
    assertEquals(
        boolean.class,
        JevEvaluator.class.getMethod("test", String.class, Jev.NoulQuestion.class).getReturnType());
    for (int arity = 2; arity <= 8; arity++) {
      Class<?>[] parameters = new Class<?>[arity + 1];
      parameters[0] = String.class;
      java.util.Arrays.fill(parameters, 1, parameters.length, Jev.Question.class);
      assertEquals(
          "io.github.maxsumrall.jev4j.JevEvaluator$Evaluation" + arity,
          JevEvaluator.class.getMethod("evaluate", parameters).getReturnType().getName());
    }
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
            // Compile against the installed public API alone, without Jackson on the classpath.
            Jev.class.getProtectionDomain().getCodeSource().getLocation().getPath(),
            "-d",
            output.toString());
    return compiler.getTask(null, null, diagnostic -> {}, options, null, List.of(unit)).call();
  }
}
