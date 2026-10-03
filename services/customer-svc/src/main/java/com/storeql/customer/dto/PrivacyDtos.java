package com.storeql.customer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Request and response DTOs for a person's privacy under the DPDP Act (13.12). */
public final class PrivacyDtos {

  private PrivacyDtos() {}

  @Schema(name = "PrivacySettings", description = "Who takes grievances, and the published period.")
  public record SettingsResponse(
      String grievanceName,
      String grievanceEmail,
      String grievancePhone,
      String grievanceAddress,
      @Schema(description = "Days the business gives itself to answer a request: at most 90.")
          int responseDays,
      boolean hasGrievanceContact,
      String updatedAt) {}

  @Schema(name = "SetPrivacySettingsRequest")
  public record SetSettingsRequest(
      @Size(max = 120) String grievanceName,
      @Size(max = 200) String grievanceEmail,
      @Size(max = 200) String grievancePhone,
      @Size(max = 500) String grievanceAddress,
      @Schema(description = "1 to 90; leaving it out keeps what is set.") Integer responseDays) {}

  @Schema(
      name = "PrivacyNotice",
      description = "One published version of the notice in one language.")
  public record NoticeResponse(
      String id,
      String language,
      String languageName,
      int version,
      String title,
      String body,
      String publishedAt) {}

  @Schema(name = "PublishPrivacyNoticeRequest")
  public record PublishNoticeRequest(
      @NotNull @Schema(description = "en, or an Eighth Schedule language's ISO 639 code.")
          String language,
      @NotNull @Size(max = 200) String title,
      @NotNull @Size(max = 20_000) String body) {}

  @Schema(name = "PrivacyLanguage")
  public record LanguageResponse(String code, String name, boolean published) {}

  @Schema(name = "PrivacyPurpose")
  public record PurposeResponse(
      String code,
      String text,
      @Schema(description = "Whether it tracks, profiles or targets: never for a child.")
          boolean tracking) {}

  @Schema(
      name = "PrivacyNoticeView",
      description =
          "The notice as a shopper reads it: in the language asked for when published, else in"
              + " English, else none yet; with the purposes, the languages offered, the grievance"
              + " contact and the period, and whether the DPDP Act binds this business today.")
  public record NoticeViewResponse(
      String requested,
      String served,
      NoticeResponse notice,
      List<LanguageResponse> languages,
      List<PurposeResponse> purposes,
      SettingsResponse settings,
      @Schema(description = "Whether India's DPDP Act binds this business today.") boolean dpdp,
      @Schema(description = "The day it starts to, when the register names one; else null.")
          String dpdpFrom) {}

  @Schema(name = "PurposeConsent")
  public record PurposeConsentResponse(
      String purpose,
      String text,
      boolean tracking,
      boolean granted,
      String noticeLanguage,
      Integer noticeVersion,
      String updatedAt) {}

  @Schema(name = "GuardianConsent")
  public record GuardianResponse(
      String guardianName,
      @Schema(description = "DETAILS_HELD, DOCUMENT_SEEN or DIGITAL_LOCKER.") String verification,
      String reference,
      String givenAt,
      String withdrawnAt,
      boolean standing) {}

  @Schema(
      name = "PrivacyConsents",
      description = "A person's consents, purpose by purpose, and what bears on them.")
  public record ConsentsResponse(
      List<PurposeConsentResponse> consents,
      @Schema(description = "Under eighteen by the date of birth held.") boolean child,
      @Schema(description = "A child's standing guardian consent, or null.")
          GuardianResponse guardian,
      @Schema(
              description =
                  "False for a child with no guardian consent: tracking purposes refused.")
          boolean canTrack,
      List<NoticeResponse> notices) {}

  @Schema(name = "ConsentChoice")
  public record ChoiceRequest(@NotNull String purpose, @NotNull Boolean granted) {}

  @Schema(name = "ChooseConsentsRequest")
  public record ChooseRequest(
      @NotNull @Size(min = 1, max = 8) List<@NotNull @Valid ChoiceRequest> choices,
      @Schema(description = "The language the person read the notice in; English when left out.")
          String language) {}

  @Schema(name = "ConsentLogEntry")
  public record ConsentEntryResponse(
      String id,
      String purpose,
      boolean granted,
      String source,
      String noticeLanguage,
      Integer noticeVersion,
      String actorId,
      String recordedAt) {}

  @Schema(name = "RecordGuardianConsentRequest")
  public record RecordGuardianRequest(
      @NotNull @Size(max = 120) String guardianName,
      @NotNull String verification,
      @Size(max = 200) @Schema(description = "What was seen or received; never a document number.")
          String reference) {}

  @Schema(name = "PrivacyRequest", description = "What a person asked for, and what became of it.")
  public record RequestResponse(
      String id,
      String customerId,
      @Schema(description = "ACCESS, CORRECTION, ERASURE, NOMINATION or GRIEVANCE.") String kind,
      String detail,
      String nomineeName,
      String nomineeContact,
      String openedAt,
      String dueOn,
      @Schema(description = "OPEN, RESOLVED or REFUSED.") String status,
      boolean overdue,
      String resolution,
      String resolvedAt,
      String resolvedBy) {}

  @Schema(name = "OpenPrivacyRequestRequest")
  public record OpenRequestRequest(
      @NotNull String kind,
      @Size(max = 2_000) String detail,
      @Size(max = 120) String nomineeName,
      @Size(max = 200) String nomineeContact) {}

  @Schema(name = "ResolvePrivacyRequestRequest")
  public record ResolveRequest(
      @NotNull @Schema(description = "RESOLVED or REFUSED.") String status,
      @NotNull @Size(max = 2_000) String resolution) {}

  @Schema(name = "BreachIntimation")
  public record IntimationResponse(
      String id,
      String noticeId,
      String subject,
      String body,
      String sentAt,
      String sentBy,
      int recipients,
      int failures) {}

  @Schema(name = "IntimateBreachRequest")
  public record IntimateRequest(
      @Schema(description = "The platform's security notice this answers, when there is one.")
          String noticeId,
      @NotNull @Size(max = 200) String subject,
      @NotNull @Size(max = 4_000) String body,
      @Schema(description = "The customers affected, up to 500; none for everyone reachable.")
          List<String> customerIds) {}
}
