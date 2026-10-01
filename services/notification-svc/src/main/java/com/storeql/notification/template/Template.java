package com.storeql.notification.template;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The words of one message, with gaps for what changes (13.x, message templates): {@code {{name}}}
 * for a value, {@code {{#name}}…{{/name}}} for a part shown when the value is there (and repeated
 * for each item of a list), {@code {{^name}}…{{/name}}} for a part shown when it is not.
 *
 * <p>Deliberately nothing more. A business writes these, so a template can do no more than fill
 * gaps: no code, no includes, no way to reach anything but the values the platform hands it. It is
 * plain text, never HTML, so there is nothing to escape and nothing to inject. A template that does
 * not parse is refused when it is saved, not when a message is due.
 */
public final class Template {

  /** A template that does not parse, and where. */
  public static final class Invalid extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    Invalid(String message) {
      super(message);
    }
  }

  private sealed interface Node permits Text, Value, Section {}

  private record Text(String text) implements Node {}

  private record Value(String name) implements Node {}

  private record Section(String name, boolean inverted, List<Node> body) implements Node {}

  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");

  /**
   * Parsed templates by source text (a Template is immutable once built). Bounded: the catalogue's
   * texts are constants and a business's are few, but a full cache is simply emptied.
   */
  private static final java.util.concurrent.ConcurrentMap<String, Template> PARSED =
      new ConcurrentHashMap<>();

  private static final int CACHE_MAX = cacheMax();

  private static int cacheMax() {
    try {
      return org.eclipse.microprofile.config.ConfigProvider.getConfig()
          .getOptionalValue("storeql.notification.template-cache-size", Integer.class)
          .orElse(512);
    } catch (RuntimeException e) {
      return 512;
    }
  }

  private final List<Node> nodes;

  private Template(List<Node> nodes) {
    this.nodes = nodes;
  }

  /**
   * Parses a template.
   *
   * @throws Invalid when a gap is unclosed or empty, or a section is closed that was not opened
   */
  public static Template parse(String source) {
    Template cached = PARSED.get(source);
    if (cached != null) return cached;
    Template parsed = parseUncached(source);
    if (CACHE_MAX > 0) {
      if (PARSED.size() >= CACHE_MAX) PARSED.clear();
      PARSED.put(source, parsed);
    }
    return parsed;
  }

  private static Template parseUncached(String source) {
    List<List<Node>> stack = new ArrayList<>();
    List<Section> open = new ArrayList<>();
    List<Node> current = new ArrayList<>();
    int i = 0;
    while (i < source.length()) {
      int start = source.indexOf("{{", i);
      if (start < 0) {
        current.add(new Text(source.substring(i)));
        break;
      }
      if (start > i) current.add(new Text(source.substring(i, start)));
      int end = source.indexOf("}}", start + 2);
      if (end < 0) throw new Invalid("a {{ at character " + (start + 1) + " is never closed");
      String tag = source.substring(start + 2, end).strip();
      if (tag.isEmpty()) throw new Invalid("an empty {{}} at character " + (start + 1));
      char kind = tag.charAt(0);
      String name = (kind == '#' || kind == '^' || kind == '/') ? tag.substring(1).strip() : tag;
      if (!NAME.matcher(name).matches()) {
        throw new Invalid(
            "{{" + tag + "}} at character " + (start + 1) + " is not a name this template knows");
      }
      switch (kind) {
        case '#', '^' -> {
          Section s = new Section(name, kind == '^', new ArrayList<>());
          current.add(s);
          stack.add(current);
          open.add(s);
          current = s.body();
        }
        case '/' -> {
          if (open.isEmpty() || !open.get(open.size() - 1).name().equals(name)) {
            throw new Invalid(
                "{{/" + name + "}} at character " + (start + 1) + " closes nothing that is open");
          }
          open.remove(open.size() - 1);
          current = stack.remove(stack.size() - 1);
        }
        default -> current.add(new Value(name));
      }
      i = end + 2;
    }
    if (!open.isEmpty()) {
      throw new Invalid("{{#" + open.get(open.size() - 1).name() + "}} is never closed");
    }
    return new Template(List.copyOf(current));
  }

  /** Every name the template uses, section names included, in the order they first appear. */
  public Set<String> names() {
    Set<String> out = new LinkedHashSet<>();
    collect(nodes, out);
    return Collections.unmodifiableSet(out);
  }

  private static void collect(List<Node> nodes, Set<String> out) {
    for (Node n : nodes) {
      switch (n) {
        case Value v -> out.add(v.name());
        case Section s -> {
          out.add(s.name());
          collect(s.body(), out);
        }
        case Text t -> {}
      }
    }
  }

  /**
   * Fills the gaps.
   *
   * @param scope what a name stands for: a string, a boolean, a list of scopes for a repeated
   *     section, or null for nothing. Inside a list, a name is looked up in the item first and in
   *     the scope around it after.
   */
  public String render(Function<String, Object> scope) {
    StringBuilder out = new StringBuilder();
    render(nodes, scope, out);
    return out.toString();
  }

  @SuppressWarnings("unchecked")
  private static void render(List<Node> nodes, Function<String, Object> scope, StringBuilder out) {
    for (Node n : nodes) {
      switch (n) {
        case Text t -> out.append(t.text());
        case Value v -> {
          Object value = scope.apply(v.name());
          if (value != null && !(value instanceof Boolean) && !(value instanceof List)) {
            out.append(value);
          }
        }
        case Section s -> {
          Object value = scope.apply(s.name());
          boolean present = present(value);
          if (s.inverted()) {
            if (!present) render(s.body(), scope, out);
          } else if (value instanceof List<?> items) {
            for (Object item : items) {
              Function<String, Object> itemScope = (Function<String, Object>) item;
              render(
                  s.body(),
                  name -> {
                    Object own = itemScope.apply(name);
                    return own != null ? own : scope.apply(name);
                  },
                  out);
            }
          } else if (present) {
            render(s.body(), scope, out);
          }
        }
      }
    }
  }

  private static boolean present(Object value) {
    return switch (value) {
      case null -> false;
      case Boolean b -> b;
      case List<?> l -> !l.isEmpty();
      case String s -> !s.isEmpty();
      default -> true;
    };
  }
}
