package club.ttg.systemregistry.catalog.api;

import club.ttg.systemregistry.manifest.SystemManifest;
import club.ttg.systemregistry.submission.SystemSubmission;

import java.time.Instant;

/**
 * Система в каталоге VTTG.
 *
 * {@code version} и {@code downloadUrl} — снимок на момент последней
 * синхронизации; актуальные значения VTTG берёт из {@code manifestUrl},
 * как при установке системы по ссылке.
 */
public record CatalogSystemResponse(
        String id,
        String name,
        String version,
        String description,
        String author,
        SystemManifest.Compatibility compatibility,
        String repositoryUrl,
        String manifestUrl,
        String downloadUrl,
        Instant approvedAt,
        Instant updatedAt
) {

    public static CatalogSystemResponse from(SystemSubmission submission) {
        return new CatalogSystemResponse(
                submission.getSystemId(),
                submission.getSystemName(),
                submission.getSystemVersion(),
                submission.getCatalogDescription(),
                submission.getSystemAuthor() != null ? submission.getSystemAuthor() : submission.getAuthorName(),
                submission.getCompatibility(),
                submission.getRepositoryUrl(),
                submission.getManifestUrl(),
                submission.getDownloadUrl(),
                submission.getReviewedAt(),
                submission.getUpdatedAt()
        );
    }
}
