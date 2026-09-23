#!/usr/bin/env python3
"""Regenerate the arity-specific multi-question API in JevEvaluator.java."""

from pathlib import Path

START = "  // BEGIN GENERATED MULTI-QUESTION API\n"
END = "  // END GENERATED MULTI-QUESTION API\n"
path = Path(__file__).parent / "src/main/java/io/github/maxsumrall/jev4j/JevEvaluator.java"


def generated() -> str:
    parts = [START]
    for n in range(2, 9):
        types = ", ".join(f"A{i}" for i in range(1, n + 1))
        parameters = ",\n      ".join(
            f"Jev.Question<A{i}> question{i}" for i in range(1, n + 1)
        )
        preparations = "\n".join(
            f"    Prepared<A{i}> prepared{i} = prepare(question{i});" for i in range(1, n + 1)
        )
        prepared_list = ", ".join(f"prepared{i}" for i in range(1, n + 1))
        answers = ",\n        ".join(
            f"prepared{i}.cast(result.answer().get({i - 1}))" for i in range(1, n + 1)
        )
        fields = ",\n      ".join(f"A{i} answer{i}" for i in range(1, n + 1))
        nulls = "\n".join(
            f'      Objects.requireNonNull(answer{i}, "answer{i}");' for i in range(1, n + 1)
        )
        apply = ", ".join(f"answer{i}" for i in range(1, n + 1))
        function_parameters = ", ".join(f"A{i} answer{i}" for i in range(1, n + 1))
        parts.append(
            f"""
  public <{types}> Evaluation{n}<{types}> evaluate(
      String state,
      {parameters}) {{
{preparations}
    Evaluation<List<Object>> result = exchange(List.of({prepared_list}), state, "question1");
    return new Evaluation{n}<>({answers},
        result.model(), result.usage(), result.id(), result.provider(), result.requestId());
  }}

  @FunctionalInterface
  public interface Function{n}<{types}, R> {{
    R apply({function_parameters});
  }}

  public record Evaluation{n}<{types}>(
      {fields},
      String model,
      Usage usage,
      Optional<String> id,
      Optional<String> provider,
      Optional<String> requestId) {{
    public Evaluation{n}({function_parameters}, String model, Usage usage,
        Optional<String> id, Optional<String> provider) {{
      this({apply}, model, usage, id, provider, Optional.empty());
    }}

    public Evaluation{n} {{
{nulls}
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(usage, "usage");
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(provider, "provider");
      Objects.requireNonNull(requestId, "requestId");
    }}

    public <R> R map(Function{n}<? super {', ? super '.join(f'A{i}' for i in range(1, n + 1))}, ? extends R> mapper) {{
      return Objects.requireNonNull(mapper, "mapper").apply({apply});
    }}
  }}
"""
        )
    parts.append(END)
    return "".join(parts)


source = path.read_text()
replacement = generated()
if START in source:
    before, remainder = source.split(START, 1)
    _, after = remainder.split(END, 1)
    source = before + replacement + after
else:
    anchor = "  private interface AnswerDecoder<T> {\n"
    source = source.replace(anchor, replacement + "\n" + anchor)
path.write_text(source)
