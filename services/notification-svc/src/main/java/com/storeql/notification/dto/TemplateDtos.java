package com.storeql.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** A business's message templates (13.x): what there is to write, and the words written. */
public final class TemplateDtos {

  private TemplateDtos() {}

  @Schema(name = "MessageVariable", description = "A value a template can use, as {{name}}.")
  public record VariableView(
      String name,
      @Schema(description = "TEXT, MONEY, NUMBER, DAY, MOMENT, FLAG or LIST") String kind,
      String description) {}

  @Schema(name = "MessageWritten", description = "A language the business has written a form in.")
  public record Written(String language, int version, String createdAt) {}

  @Schema(name = "MessageForm", description = "One form a message goes out in.")
  public record FormView(
      @Schema(description = "EMAIL, SMS, PUSH or ALERT") String form,
      @Schema(description = "The longest subject it shows; 0 for none") int subjectMax,
      int bodyMax,
      @Schema(
              description =
                  "Parts the template must keep: one name of each group. The recall notice's are"
                      + " the law's (GPSR art.36).")
          List<List<String>> required,
      @Schema(description = "The languages the business has written it in; none is the platform's")
          List<Written> written) {

    public FormView {
      required = List.copyOf(required);
      written = List.copyOf(written);
    }
  }

  @Schema(name = "MessageType", description = "A message a business can put in its own words.")
  public record MessageView(
      String type,
      String title,
      @Schema(description = "CUSTOMER, STAFF or SUPPLIER") String audience,
      String why,
      List<VariableView> variables,
      List<FormView> forms) {

    public MessageView {
      variables = List.copyOf(variables);
      forms = List.copyOf(forms);
    }
  }

  @Schema(name = "MessageTemplateVersion")
  public record Version(
      int version,
      String subject,
      String body,
      String createdAt,
      @Schema(description = "When it stopped being used; null while live") String retiredAt) {}

  @Schema(name = "MessageTemplate", description = "The words a message goes out in now.")
  public record TemplateView(
      String type,
      String form,
      @Schema(description = "The language asked for") String language,
      @Schema(
              description =
                  "The language the words shown are written in: the one asked for, the business's"
                      + " default language, or the platform's (en)")
          String wordsLanguage,
      @Schema(
              description =
                  "BUSINESS for the business's own words in this language; DEFAULT_LANGUAGE when a"
                      + " message in this language goes out in the business's words in its default"
                      + " language instead, because it has no live version in this one (never"
                      + " written, or retired) or its live version cannot be used"
                      + " (storedWordsUnusable); DEFAULT for the platform's. Whichever it is, the"
                      + " words shown are the ones that go out, and the starting point for writing"
                      + " the language's own")
          String source,
      @Schema(
              description =
                  "True when the business has a live version in this language whose words do not"
                      + " parse, so it is passed over and the words shown are the next ones in the"
                      + " order. It is still in the history; saving a new version replaces it")
          boolean storedWordsUnusable,
      @Schema(
              description =
                  "The version of the business's words shown; null for the platform's words")
          Integer version,
      String subject,
      String body,
      List<Version> history) {

    public TemplateView {
      history = List.copyOf(history);
    }
  }

  @Schema(name = "MessageTemplateRequest")
  public record TemplateRequest(
      @Schema(description = "Ignored for a text message, which has no subject") @Size(max = 400)
          String subject,
      @NotBlank @Size(max = 40_000) String body) {}

  @Schema(name = "MessageTemplateProblem")
  public record PreviewProblem(String code, String message, List<String> names) {

    public PreviewProblem {
      names = List.copyOf(names);
    }
  }

  @Schema(name = "MessageTemplatePreview", description = "A draft written out with sample values.")
  public record Preview(
      String subject,
      String body,
      @Schema(description = "For a text message: how many messages it takes") Integer smsSegments,
      @Schema(description = "What would stop it being saved; empty when nothing")
          List<PreviewProblem> problems) {

    public Preview {
      problems = List.copyOf(problems);
    }
  }

  @Schema(name = "MessageSettings")
  public record Settings(
      @Schema(
              description =
                  "ISO 639: what messages go out in when the reader's language is not known")
          @NotBlank
          @Size(max = 3)
          String defaultLanguage,
      @Schema(description = "What messages are signed with; empty for the business's own name")
          @Size(max = 120)
          String signOff,
      @Schema(description = "What they are signed with now", readOnly = true) String signedAs) {}
}
