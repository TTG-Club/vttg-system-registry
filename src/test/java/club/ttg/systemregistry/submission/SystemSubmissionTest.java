package club.ttg.systemregistry.submission;

import club.ttg.systemregistry.manifest.InvalidManifestException;
import club.ttg.systemregistry.manifest.SystemManifest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemSubmissionTest {

    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");
    private static final UUID AUTHOR = UUID.randomUUID();
    private static final UUID MODERATOR = UUID.randomUUID();

    static SystemManifest manifest(String id, String version) {
        return new SystemManifest(id, "Система", version, "Из манифеста", null, SystemManifest.Compatibility.NONE,
                "https://github.com/a/b/releases/download/v" + version + "/s.zip", null, "{}");
    }

    static SystemSubmission pending() {
        return SystemSubmission.submit(AUTHOR, "author", "https://github.com/a/b",
                "https://raw.githubusercontent.com/a/b/main/system.json", " Описание ",
                manifest("m", "1.0.0"), NOW);
    }

    @Test
    void newSubmissionIsPending() {
        SystemSubmission submission = pending();

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.PENDING);
        assertThat(submission.getDescription()).isEqualTo("Описание");
        assertThat(submission.isOwnedBy(AUTHOR)).isTrue();
    }

    @Test
    void approvesPending() {
        SystemSubmission submission = pending();

        submission.approve(MODERATOR, "  ", NOW);

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.APPROVED);
        assertThat(submission.getModeratorId()).isEqualTo(MODERATOR);
        assertThat(submission.getModerationComment()).isNull();
        assertThat(submission.getReviewedAt()).isEqualTo(NOW);
    }

    @Test
    void rejectRequiresComment() {
        SystemSubmission submission = pending();

        assertThatThrownBy(() -> submission.reject(MODERATOR, " ", NOW))
                .isInstanceOf(InvalidSubmissionStateException.class);

        submission.reject(MODERATOR, "Нет README", NOW);
        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.REJECTED);
        assertThat(submission.getModerationComment()).isEqualTo("Нет README");
    }

    @Test
    void approvedSystemCanBeTakenDownButNotReapproved() {
        SystemSubmission submission = pending();
        submission.approve(MODERATOR, null, NOW);

        submission.reject(MODERATOR, "Нарушает правила", NOW);

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.REJECTED);
        assertThatThrownBy(() -> submission.approve(MODERATOR, null, NOW))
                .isInstanceOf(InvalidSubmissionStateException.class);
    }

    @Test
    void resubmitAfterRejectionReturnsToQueueAndClearsDecision() {
        SystemSubmission submission = pending();
        submission.reject(MODERATOR, "Нет README", NOW);

        submission.resubmit("https://github.com/a/b", "https://raw.githubusercontent.com/a/b/main/system.json",
                "Новое описание", manifest("m", "1.0.1"), NOW);

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.PENDING);
        assertThat(submission.getModerationComment()).isNull();
        assertThat(submission.getReviewedAt()).isNull();
        assertThat(submission.getSystemVersion()).isEqualTo("1.0.1");
    }

    @Test
    void approvedSubmissionCannotBeEdited() {
        SystemSubmission submission = pending();
        submission.approve(MODERATOR, null, NOW);

        assertThatThrownBy(() -> submission.resubmit("https://github.com/a/b",
                "https://raw.githubusercontent.com/a/b/main/system.json", "x", 
                manifest("m", "1.0.1"), NOW))
                .isInstanceOf(InvalidSubmissionStateException.class);
    }

    @Test
    void refreshKeepsStatusButRefusesIdChange() {
        SystemSubmission submission = pending();
        submission.approve(MODERATOR, null, NOW);

        submission.refreshManifest(manifest("m", "2.0.0"), NOW);
        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.APPROVED);
        assertThat(submission.getSystemVersion()).isEqualTo("2.0.0");

        assertThatThrownBy(() -> submission.refreshManifest(manifest("other", "2.0.0"), NOW))
                .isInstanceOf(InvalidSubmissionStateException.class);
    }

    @Test
    void linksAreEditableUntilFirstApproval() {
        SystemSubmission submission = pending();
        assertThat(submission.isLinksLocked()).isFalse();

        submission.resubmit("https://github.com/a/c", "https://raw.githubusercontent.com/a/c/main/system.json",
                "x", manifest("m", "1.0.0"), NOW);

        assertThat(submission.getRepositoryUrl()).isEqualTo("https://github.com/a/c");
    }

    @Test
    void approvalLocksLinksEvenAfterTakeDown() {
        SystemSubmission submission = pending();
        submission.approve(MODERATOR, null, NOW);
        submission.reject(MODERATOR, "Нарушает правила", NOW);

        assertThat(submission.isLinksLocked()).isTrue();
        assertThat(submission.getApprovedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> submission.resubmit("https://github.com/a/other",
                "https://raw.githubusercontent.com/a/other/main/system.json", "x", 
                manifest("m", "1.0.1"), NOW))
                .isInstanceOf(InvalidSubmissionStateException.class)
                .hasMessageContaining("новую заявку");

        // Описание с прежними ссылками поправить можно.
        submission.resubmit("https://github.com/a/b", "https://raw.githubusercontent.com/a/b/main/system.json",
                "Исправил", manifest("m", "1.0.1"), NOW);
        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.PENDING);
        assertThat(submission.isLinksLocked()).isTrue();
    }

    @Test
    void supersededSubmissionIsClosed() {
        SystemSubmission submission = pending();
        assertThatThrownBy(() -> submission.supersede(NOW)).isInstanceOf(InvalidSubmissionStateException.class);

        submission.approve(MODERATOR, null, NOW);
        submission.supersede(NOW);

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.SUPERSEDED);
        assertThatThrownBy(() -> submission.withdraw(NOW)).isInstanceOf(InvalidSubmissionStateException.class);
        assertThatThrownBy(() -> submission.refreshManifest(manifest("m", "2.0.0"), NOW))
                .isInstanceOf(InvalidSubmissionStateException.class);
    }

    @Test
    void withdrawIsFinal() {
        SystemSubmission submission = pending();

        submission.withdraw(NOW);

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.WITHDRAWN);
        assertThat(submission.getStatus().isLive()).isFalse();
        assertThatThrownBy(() -> submission.withdraw(NOW)).isInstanceOf(InvalidSubmissionStateException.class);
        assertThatThrownBy(() -> submission.approve(MODERATOR, null, NOW))
                .isInstanceOf(InvalidSubmissionStateException.class);
    }

    @Test
    void catalogDescriptionFallsBackToManifest() {
        SystemSubmission submission = SystemSubmission.submit(AUTHOR, "author", "https://github.com/a/b",
                "https://raw.githubusercontent.com/a/b/main/system.json", "  ", manifest("m", "1.0.0"), NOW);

        assertThat(submission.getDescription()).isNull();
        assertThat(submission.getCatalogDescription()).isEqualTo("Из манифеста");
        assertThat(pending().getCatalogDescription()).isEqualTo("Описание");
    }

    @Test
    void descriptionIsRequiredSomewhere() {
        SystemManifest withoutDescription = new SystemManifest("m", "Система", "1.0.0", null, null,
                SystemManifest.Compatibility.NONE, "https://github.com/a/b/releases/download/v1/s.zip", null, "{}");

        assertThatThrownBy(() -> SystemSubmission.submit(AUTHOR, "author", "https://github.com/a/b",
                "https://raw.githubusercontent.com/a/b/main/system.json", null, withoutDescription, NOW))
                .isInstanceOf(InvalidManifestException.class)
                .hasMessageContaining("description");
    }
}
