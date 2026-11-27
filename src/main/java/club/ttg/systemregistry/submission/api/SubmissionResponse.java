package club.ttg.systemregistry.submission.api;

import club.ttg.systemregistry.manifest.SystemManifest;
import club.ttg.systemregistry.submission.SubmissionStatus;
import club.ttg.systemregistry.submission.SystemSubmission;

import java.time.Instant;
import java.util.UUID;

/** Заявка целиком — для автора и модератора. */
public record SubmissionResponse(
        UUID id,
        SubmissionStatus status,
        UUID authorId,
        String authorName,
        String repositoryUrl,
        String manifestUrl,
        String description,
        SystemInfo system,
        Moderation moderation,
        /** Ссылки зафиксированы первым одобрением: сменить их можно только новой заявкой. */
        boolean linksLocked,
        Instant approvedAt,
        Instant createdAt,
        Instant updatedAt
) {

    /** Снимок {@code system.json}; {@code manifest} — документ как пришёл. */
    public record SystemInfo(
            String id,
            String name,
            String version,
            String author,
            String description,
            SystemManifest.Compatibility compatibility,
            String downloadUrl,
            String manifest,
            Instant syncedAt
    ) {
    }

    public record Moderation(UUID moderatorId, String comment, Instant reviewedAt) {
    }

    public static SubmissionResponse from(SystemSubmission submission) {
        return new SubmissionResponse(
                submission.getId(),
                submission.getStatus(),
                submission.getAuthorId(),
                submission.getAuthorName(),
                submission.getRepositoryUrl(),
                submission.getManifestUrl(),
                submission.getDescription(),
                new SystemInfo(
                        submission.getSystemId(),
                        submission.getSystemName(),
                        submission.getSystemVersion(),
                        submission.getSystemAuthor(),
                        submission.getSystemDescription(),
                        submission.getCompatibility(),
                        submission.getDownloadUrl(),
                        submission.getManifestJson(),
                        submission.getManifestSyncedAt()
                ),
                submission.getReviewedAt() == null ? null : new Moderation(
                        submission.getModeratorId(),
                        submission.getModerationComment(),
                        submission.getReviewedAt()
                ),
                submission.isLinksLocked(),
                submission.getApprovedAt(),
                submission.getCreatedAt(),
                submission.getUpdatedAt()
        );
    }
}
