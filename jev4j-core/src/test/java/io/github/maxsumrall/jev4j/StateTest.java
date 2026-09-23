package io.github.maxsumrall.jev4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class StateTest {
  private static final JsonMapper JSON = new JsonMapper();

  public record Ticket(List<Map<String, int[]>> attempts) {}

  public record Broken(String secret) {
    @Override
    public String secret() {
      throw new IllegalStateException(secret);
    }
  }

  @Test
  void snapshotDetachesMutableValuesInsideRecordsAndRequestCopies() {
    int[] counts = {2, 5};
    Map<String, int[]> attempt = new LinkedHashMap<>();
    attempt.put("counts", counts);
    List<Map<String, int[]>> attempts = new ArrayList<>();
    attempts.add(attempt);
    Ticket ticket = new Ticket(attempts);
    Jev.State state = Jev.State.from(ticket);

    counts[0] = 9;
    attempt.put("later", new int[] {7});
    attempts.add(Map.of("added", new int[] {8}));
    assertEquals(JSON.readTree("{\"attempts\":[{\"counts\":[2,5]}]}"), value(state));
    assertEquals(9, value(Jev.State.from(ticket)).at("/attempts/0/counts/0").intValue());

    ObjectNode requestCopy = (ObjectNode) value(state);
    requestCopy.remove("attempts");
    assertEquals(2, value(state).at("/attempts/0/counts/0").intValue());

    ObjectNode callerNode = JSON.createObjectNode().put("message", "before");
    Jev.State nodeSnapshot = Jev.State.from(callerNode);
    callerNode.put("message", "after");
    assertEquals("before", value(nodeSnapshot).path("message").stringValue());
  }

  @Test
  void numbersAndNestedNullSurviveBothFactoriesWithoutDoubleRounding() {
    String decimal = "123456789.12345678901234567890";
    String integer = "123456789012345678901234567890";
    Jev.State converted =
        Jev.State.from(
            Map.of(
                "decimal",
                new BigDecimal(decimal),
                "integer",
                new BigInteger(integer),
                "large",
                new BigDecimal("1E+400")));
    Jev.State parsed =
        Jev.State.fromJson(
            "{\"decimal\":"
                + decimal
                + ",\"integer\":"
                + integer
                + ",\"large\":1e400,\"null\":null,\"values\":[true,null,0.1]}");
    for (Jev.State state : List.of(converted, parsed)) {
      assertEquals(new BigDecimal(decimal), value(state).path("decimal").decimalValue());
      assertEquals(new BigInteger(integer), value(state).path("integer").bigIntegerValue());
      assertEquals(new BigDecimal("1E+400"), value(state).path("large").decimalValue());
    }
    assertTrue(value(parsed).path("null").isNull());
    assertTrue(value(parsed).at("/values/0").booleanValue());
    assertTrue(value(parsed).at("/values/1").isNull());
    assertEquals(new BigDecimal("0.1"), value(parsed).at("/values/2").decimalValue());
    assertEquals(JSON.readTree("[null,2]"), value(Jev.State.from(new Object[] {null, 2})));
  }

  @Test
  void literalTextIsNeverParsedAndEmptyStatesAreValid() {
    assertEquals("{\"x\":1}", value(Jev.State.from("{\"x\":1}")).stringValue());
    assertTrue(value(Jev.State.fromJson("{\"x\":1}")).isObject());
    assertEquals("text", value(Jev.State.fromJson("\"text\"")).stringValue());
    assertEquals("", value(Jev.State.from("")).stringValue());
    assertEquals(" ", value(Jev.State.from(" ")).stringValue());
    assertTrue(value(Jev.State.from(Map.of())).isObject());
    assertTrue(value(Jev.State.from(List.of())).isArray());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "1",
        "true",
        "",
        " ",
        "{} []",
        "[] secret",
        "{secret}",
        "{\"x\":1,\"x\":2}",
        "[NaN]",
        "[Infinity]",
        "[-Infinity]"
      })
  void rejectsInvalidJsonAndRootsWithSanitizedErrors(String json) {
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> Jev.State.fromJson(json));
    assertEquals(
        "invalid state: expected JSON text, object, or array within JSON limits",
        error.getMessage());
    assertNull(error.getCause());
  }

  @Test
  void rejectsNonFiniteValuesBeforeTheyCanBecomeQuotedStrings() {
    for (Number number :
        List.of(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY)) {
      assertThrows(IllegalArgumentException.class, () -> Jev.State.from(Map.of("secret", number)));
    }
    assertThrows(
        IllegalArgumentException.class, () -> Jev.State.from(new double[] {1, Double.NaN}));
    assertThrows(IllegalArgumentException.class, () -> Jev.State.from(2));
    assertThrows(IllegalArgumentException.class, () -> Jev.State.from(false));
    // Text that names a non-finite value is still ordinary text.
    assertEquals("NaN", value(Jev.State.fromJson("\"NaN\"")).stringValue());
  }

  @Test
  void cyclesFailButSharedNoncyclicChildrenAreValid() {
    List<Object> list = new ArrayList<>();
    list.add(list);
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("self", map);
    assertThrows(IllegalArgumentException.class, () -> Jev.State.from(list));
    assertThrows(IllegalArgumentException.class, () -> Jev.State.from(map));
    record Node(List<Object> children) {}
    List<Object> children = new ArrayList<>();
    Node node = new Node(children);
    children.add(node);
    assertThrows(IllegalArgumentException.class, () -> Jev.State.from(node));
    Map<String, Integer> shared = Map.of("x", 3);
    assertEquals(
        JSON.readTree("[{\"x\":3},{\"x\":3}]"), value(Jev.State.from(List.of(shared, shared))));
  }

  @Test
  void depthBoundAndSerializationFailuresAreLocalAndSanitized() {
    String atLimit = "[".repeat(128) + "0" + "]".repeat(128);
    assertTrue(value(Jev.State.fromJson(atLimit)).isArray());
    assertThrows(IllegalArgumentException.class, () -> Jev.State.fromJson("[" + atLimit + "]"));
    for (Object input : List.of(new Object(), new Broken("secret application data"))) {
      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> Jev.State.from(input));
      assertEquals(
          "invalid state: expected JSON text, object, or array within JSON limits",
          error.getMessage());
      assertNull(error.getCause());
    }
  }

  private static JsonNode value(Jev.State state) {
    ObjectNode request = JSON.createObjectNode();
    state.putInto(request);
    return request.path("state");
  }
}
