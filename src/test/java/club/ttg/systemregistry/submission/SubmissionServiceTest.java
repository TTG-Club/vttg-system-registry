package club.ttg.systemregistry.submission;

import club.ttg.systemregistry.manifest.InvalidManifestException;
import club.ttg.systemregistry.manifest.ManifestFetcher;
import club.ttg.systemregistry.manifest.SystemManifest;
import club.ttg.systemregistry.manifest.UrlPolicy;
import club.ttg.systemregistry.manifest.UrlPolicyTest;
import club.ttg.systemregistry.submission.api.SubmissionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SubmissionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");
    private static final UUID AUTHOR = UUID.randomUUID();
    private static final UUID MODERATOR = UUID.randomUUID();
    private static final String REPOSITORY = "https://github.com/a/b";
    private static final String MANIFEST = "https://github.com/a/b/releases/latest/download/system.json";

    private final SystemSubmissionRepository repository = mock(SystemSubmissionRepository.class);
    private final ManifestFetcher manifestFetcher = mock(ManifestFetcher.class);
    private SubmissionService service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        given(transactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());

        service = new SubmissionService(
                repository,
                manifestFetcher,
                new UrlPolicy(UrlPolicyTest.properties()),
                new TransactionTemplate(transactionManager),
                Clock.fixed(NOW, ZoneOffset.UTC));

        given(manifestFetcher.resolveManifestUrl(any())).willAnswer(call -> URI.create(call.getArgument(0)));
        given(repository.save(any())).willAnswer(call -> call.getArgument(0));
    }

    @Test
    void submitsSystemFromReleaseAsset() {
        given(repository.findLiveBySystemId("s")).willReturn(List.of());
        given(manifestFetcher.fetch(any())).willReturn(manifest(
                REPOSITORY + "/releases/download/v1/s.zip", MANIFEST));

        var response = service.submit(AUTHOR, "author", request(MANIFEST));

        assertThat(response.status()).isEqualTo(SubmissionStatus.PENDING);
        assertThat(response.repositoryUrl()).isEqualTo(REPOSITORY);
        assertThat(response.system().id()).isEqualTo("s");
    }

    @Test
    void authorCanSubmitReplacementForOwnApprovedSystem() {
        SystemSubmission approved = approved(REPOSITORY, MANIFEST);
        given(repository.findLiveBySystemId("s")).willReturn(List.of(approved));
        given(manifestFetcher.fetch(any())).willReturn(manifest("https://github.com/a/new/releases/download/v1/s.zip", null));

        var response = service.submit(AUTHOR, "author",
                request("https://raw.githubusercontent.com/a/new/main/system.json"));

        assertThat(response.status()).isEqualTo(SubmissionStatus.PENDING);
        assertThat(response.repositoryUrl()).isEqualTo("https://github.com/a/new");
    }

    @Test
    void secondPendingSubmissionIsRefused() {
        SystemSubmission pending = pending(REPOSITORY, MANIFEST);
        given(repository.findLiveBySystemId("s")).willReturn(List.of(pending));
        given(manifestFetcher.fetch(any())).willReturn(manifest(REPOSITORY + "/releases/download/v1/s.zip", null));

        assertThatThrownBy(() -> service.submit(AUTHOR, "author", request(MANIFEST)))
                .isInstanceOf(SystemIdTakenException.class)
                .hasMessageContaining("на рассмотрении");
    }

    @Test
    void foreignSystemIdIsRefused() {
        SystemSubmission foreign = SystemSubmission.submit(UUID.randomUUID(), "other", REPOSITORY, MANIFEST,
                "x", manifest(REPOSITORY + "/releases/download/v1/s.zip", null), NOW);
        given(repository.findLiveBySystemId("s")).willReturn(List.of(foreign));
        given(manifestFetcher.fetch(any())).willReturn(manifest(REPOSITORY + "/releases/download/v1/s.zip", null));

        assertThatThrownBy(() -> service.submit(AUTHOR, "author", request(MANIFEST)))
                .isInstanceOf(SystemIdTakenException.class)
                .hasMessageContaining("другим автором");
    }

    @Test
    void archiveAndUpdateChannelMustLiveInManifestRepository() {
        given(repository.findLiveBySystemId("s")).willReturn(List.of());

        // Из такой ссылки не понять, в каком репозитории лежит манифест.
        assertThatThrownBy(() -> service.submit(AUTHOR, "author", request("https://github.com/a")))
                .isInstanceOf(InvalidManifestException.class)
                .hasMessageContaining("manifestUrl");

        given(manifestFetcher.fetch(any())).willReturn(manifest("https://github.com/evil/x/releases/download/v1/s.zip", null));
        assertThatThrownBy(() -> service.submit(AUTHOR, "author", request(MANIFEST)))
                .isInstanceOf(InvalidManifestException.class)
                .hasMessageContaining("download");

        // Канал обновлений «наружу» обошёл бы модерацию: VTTG читает его сам.
        given(manifestFetcher.fetch(any())).willReturn(manifest(REPOSITORY + "/releases/download/v1/s.zip",
                "https://raw.githubusercontent.com/evil/x/main/system.json"));
        assertThatThrownBy(() -> service.submit(AUTHOR, "author", request(MANIFEST)))
                .isInstanceOf(InvalidManifestException.class)
                .hasMessageContaining("manifest:");
        verify(repository, never()).save(any());
    }

    @Test
    void refreshCannotMoveArchiveOutOfRepository() {
        SystemSubmission approved = approved(REPOSITORY, MANIFEST);
        given(repository.findById(approved.getId())).willReturn(Optional.of(approved));
        given(manifestFetcher.fetch(any())).willReturn(manifest("https://github.com/evil/x/releases/download/v2/s.zip", null));

        assertThatThrownBy(() -> service.refreshManifest(approved.getId(), AUTHOR))
                .isInstanceOf(InvalidManifestException.class);
    }

    @Test
    void approvingReplacementSupersedesPreviousApproval() {
        SystemSubmission previous = approved(REPOSITORY, MANIFEST);
        SystemSubmission replacement = pending("https://github.com/a/new",
                "https://raw.githubusercontent.com/a/new/main/system.json");
        given(repository.findById(replacement.getId())).willReturn(Optional.of(replacement));
        given(repository.findApprovedBySystemId("s")).willReturn(Optional.of(previous));

        var response = service.approve(replacement.getId(), MODERATOR, null);

        assertThat(response.status()).isEqualTo(SubmissionStatus.APPROVED);
        assertThat(response.linksLocked()).isTrue();
        assertThat(previous.getStatus()).isEqualTo(SubmissionStatus.SUPERSEDED);
        verify(repository).saveAndFlush(previous);
    }

    @Test
    void approvingForeignDuplicateIsRefused() {
        SystemSubmission foreign = SystemSubmission.submit(UUID.randomUUID(), "other", REPOSITORY, MANIFEST,
                "x", manifest(REPOSITORY + "/releases/download/v1/s.zip", null), NOW);
        foreign.approve(MODERATOR, null, NOW);
        SystemSubmission mine = pending("https://github.com/a/new",
                "https://raw.githubusercontent.com/a/new/main/system.json");
        given(repository.findById(mine.getId())).willReturn(Optional.of(mine));
        given(repository.findApprovedBySystemId("s")).willReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.approve(mine.getId(), MODERATOR, null))
                .isInstanceOf(SystemIdTakenException.class);
        assertThat(mine.getStatus()).isEqualTo(SubmissionStatus.PENDING);
    }

    private static SubmissionRequest request(String manifestUrl) {
        return new SubmissionRequest(manifestUrl, "Описание");
    }

    private static SystemManifest manifest(String download, String updateManifest) {
        return new SystemManifest("s", "Система", "1.0.0", null, null,
                SystemManifest.Compatibility.NONE, download, updateManifest, "{}");
    }

    private static SystemSubmission pending(String repositoryUrl, String manifestUrl) {
        return SystemSubmission.submit(AUTHOR, "author", repositoryUrl, manifestUrl, "x",
                manifest(repositoryUrl + "/releases/download/v1/s.zip", null), NOW);
    }

    private static SystemSubmission approved(String repositoryUrl, String manifestUrl) {
        SystemSubmission submission = pending(repositoryUrl, manifestUrl);
        submission.approve(MODERATOR, null, NOW);
        return submission;
    }
}
