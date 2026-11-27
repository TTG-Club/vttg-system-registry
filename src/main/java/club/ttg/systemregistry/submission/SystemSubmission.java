package club.ttg.systemregistry.submission;

import club.ttg.systemregistry.manifest.InvalidManifestException;
import club.ttg.systemregistry.manifest.SystemManifest;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/** Заявка автора на включение игровой системы в каталог VTTG. */
@Entity
@Table(name = "system_submissions")
public class SystemSubmission {

    @Id
    private UUID id;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "author_name", length = 100)
    private String authorName;

    @Column(name = "repository_url", nullable = false, length = 2048)
    private String repositoryUrl;

    @Column(name = "manifest_url", nullable = false, length = 2048)
    private String manifestUrl;

    /** Описание из заявки; без него в каталог идёт описание из манифеста. */
    @Column(length = 1000)
    private String description;

    @Column(name = "system_id", nullable = false, length = 64)
    private String systemId;

    @Column(name = "system_name", nullable = false, length = 150)
    private String systemName;

    @Column(name = "system_version", nullable = false, length = 50)
    private String systemVersion;

    @Column(name = "system_author", length = 150)
    private String systemAuthor;

    @Column(name = "system_description", length = 2000)
    private String systemDescription;

    @Column(name = "compatibility_minimum", length = 50)
    private String compatibilityMinimum;

    @Column(name = "compatibility_verified", length = 50)
    private String compatibilityVerified;

    @Column(name = "compatibility_maximum", length = 50)
    private String compatibilityMaximum;

    @Column(name = "download_url", nullable = false, length = 2048)
    private String downloadUrl;

    @Column(name = "manifest_json", nullable = false, columnDefinition = "text")
    private String manifestJson;

    @Column(name = "manifest_synced_at", nullable = false)
    private Instant manifestSyncedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubmissionStatus status;

    @Column(name = "moderator_id")
    private UUID moderatorId;

    @Column(name = "moderation_comment", length = 2000)
    private String moderationComment;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /** Первое одобрение; с него ссылки заявки больше не меняются. */
    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected SystemSubmission() {
    }

    public static SystemSubmission submit(
            UUID authorId,
            String authorName,
            String repositoryUrl,
            String manifestUrl,
            String description,
            SystemManifest manifest,
            Instant now
    ) {
        SystemSubmission submission = new SystemSubmission();
        submission.id = UUID.randomUUID();
        submission.authorId = authorId;
        submission.authorName = authorName;
        submission.status = SubmissionStatus.PENDING;
        submission.createdAt = now;
        submission.setDetails(repositoryUrl, manifestUrl, description);
        submission.applyManifest(manifest, now);
        return submission;
    }

    /** Правка автором: заявка уходит на повторное рассмотрение. */
    public void resubmit(
            String repositoryUrl,
            String manifestUrl,
            String description,
            SystemManifest manifest,
            Instant now
    ) {
        if (status != SubmissionStatus.PENDING && status != SubmissionStatus.REJECTED) {
            throw new InvalidSubmissionStateException(
                    "Править можно только заявку на рассмотрении или отклонённую");
        }
        if (isLinksLocked()) {
            if (!this.repositoryUrl.equals(repositoryUrl) || !this.manifestUrl.equals(manifestUrl)) {
                throw new InvalidSubmissionStateException(
                        "Ссылку на system.json одобренной системы менять нельзя. Чтобы сменить её, подайте новую заявку");
            }
            requireSameSystemId(manifest);
        }
        setDetails(repositoryUrl, manifestUrl, description);
        applyManifest(manifest, now);
        status = SubmissionStatus.PENDING;
        moderatorId = null;
        moderationComment = null;
        reviewedAt = null;
    }

    /**
     * Обновляет снимок манифеста без смены статуса: новая версия системы
     * не требует повторной модерации, а id системы менять нельзя.
     */
    public void refreshManifest(SystemManifest manifest, Instant now) {
        if (status.isFinal()) {
            throw new InvalidSubmissionStateException("Заявка закрыта");
        }
        requireSameSystemId(manifest);
        applyManifest(manifest, now);
    }

    public void approve(UUID moderatorId, String comment, Instant now) {
        if (status != SubmissionStatus.PENDING) {
            throw new InvalidSubmissionStateException("Одобрить можно только заявку на рассмотрении");
        }
        decide(SubmissionStatus.APPROVED, moderatorId, comment, now);
        if (approvedAt == null) {
            approvedAt = now;
        }
    }

    /** Одобрена новая заявка автора на эту же систему — эта уходит из каталога. */
    public void supersede(Instant now) {
        if (status != SubmissionStatus.APPROVED) {
            throw new InvalidSubmissionStateException("Заменить можно только одобренную заявку");
        }
        status = SubmissionStatus.SUPERSEDED;
        updatedAt = now;
    }

    /**
     * Ссылки на репозиторий и манифест фиксируются при первом одобрении и
     * дальше не меняются — даже если систему сняли из каталога.
     */
    public boolean isLinksLocked() {
        return approvedAt != null;
    }

    /** Отклоняет заявку на рассмотрении или снимает систему из каталога. */
    public void reject(UUID moderatorId, String comment, Instant now) {
        if (status != SubmissionStatus.PENDING && status != SubmissionStatus.APPROVED) {
            throw new InvalidSubmissionStateException(
                    "Отклонить можно заявку на рассмотрении или одобренную");
        }
        if (comment == null || comment.isBlank()) {
            throw new InvalidSubmissionStateException("Укажите причину отклонения");
        }
        decide(SubmissionStatus.REJECTED, moderatorId, comment, now);
    }

    public void withdraw(Instant now) {
        if (status.isFinal()) {
            throw new InvalidSubmissionStateException("Заявка уже закрыта");
        }
        status = SubmissionStatus.WITHDRAWN;
        updatedAt = now;
    }

    public boolean isOwnedBy(UUID userId) {
        return authorId.equals(userId);
    }

    /** Описание для каталога: из заявки, а без него — из манифеста. */
    public String getCatalogDescription() {
        return description != null ? description : systemDescription;
    }

    public SystemManifest.Compatibility getCompatibility() {
        return new SystemManifest.Compatibility(compatibilityMinimum, compatibilityVerified, compatibilityMaximum);
    }

    private void requireSameSystemId(SystemManifest manifest) {
        if (!manifest.id().equals(systemId)) {
            throw new InvalidSubmissionStateException(
                    "В манифесте сменился id системы (" + systemId + " → " + manifest.id()
                            + "). Это другая система — подайте на неё отдельную заявку");
        }
    }

    private void decide(SubmissionStatus next, UUID moderatorId, String comment, Instant now) {
        this.status = next;
        this.moderatorId = moderatorId;
        this.moderationComment = comment == null || comment.isBlank() ? null : comment.strip();
        this.reviewedAt = now;
        this.updatedAt = now;
    }

    private void setDetails(String repositoryUrl, String manifestUrl, String description) {
        this.repositoryUrl = repositoryUrl;
        this.manifestUrl = manifestUrl;
        this.description = description == null || description.isBlank() ? null : description.strip();
    }

    private void applyManifest(SystemManifest manifest, Instant now) {
        if (description == null && manifest.description() == null) {
            throw new InvalidManifestException(
                    "description: нужно описание системы — в заявке или в поле description манифеста");
        }
        this.systemId = manifest.id();
        this.systemName = manifest.name();
        this.systemVersion = manifest.version();
        this.systemAuthor = manifest.author();
        this.systemDescription = manifest.description();
        this.compatibilityMinimum = manifest.compatibility().minimum();
        this.compatibilityVerified = manifest.compatibility().verified();
        this.compatibilityMaximum = manifest.compatibility().maximum();
        this.downloadUrl = manifest.download();
        this.manifestJson = manifest.json();
        this.manifestSyncedAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    public String getManifestUrl() {
        return manifestUrl;
    }

    public String getDescription() {
        return description;
    }

    public String getSystemId() {
        return systemId;
    }

    public String getSystemName() {
        return systemName;
    }

    public String getSystemVersion() {
        return systemVersion;
    }

    public String getSystemAuthor() {
        return systemAuthor;
    }

    public String getSystemDescription() {
        return systemDescription;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public String getManifestJson() {
        return manifestJson;
    }

    public Instant getManifestSyncedAt() {
        return manifestSyncedAt;
    }

    public SubmissionStatus getStatus() {
        return status;
    }

    public UUID getModeratorId() {
        return moderatorId;
    }

    public String getModerationComment() {
        return moderationComment;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
