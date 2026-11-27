package club.ttg.systemregistry.manifest;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class UrlPolicyTest {

    private final UrlPolicy policy = new UrlPolicy(properties());

    public static ManifestProperties properties() {
        return new ManifestProperties(
                List.of("github.com", "raw.githubusercontent.com", " GitLab.com "),
                List.of("release-assets.githubusercontent.com"), null, null, null);
    }

    @Test
    void acceptsHttpsOnAllowedHost() {
        assertThat(policy.require("https://gitlab.com/a/b", "repositoryUrl"))
                .isEqualTo(URI.create("https://gitlab.com/a/b"));
    }

    @Test
    void rejectsPlainHttp() {
        assertThatThrownBy(() -> policy.require("http://github.com/a/b", "repositoryUrl"))
                .isInstanceOf(InvalidManifestException.class)
                .hasMessageContaining("https");
    }

    @Test
    void rejectsForeignAndLookalikeHosts() {
        assertThatThrownBy(() -> policy.require("https://localhost/system.json", "manifestUrl"))
                .isInstanceOf(InvalidManifestException.class);
        assertThatThrownBy(() -> policy.require("https://github.com.evil.example/m.json", "manifestUrl"))
                .isInstanceOf(InvalidManifestException.class);
        assertThatThrownBy(() -> policy.require("https://169.254.169.254/latest", "manifestUrl"))
                .isInstanceOf(InvalidManifestException.class);
    }

    @Test
    void rejectsCredentialsAndCustomPorts() {
        assertThatThrownBy(() -> policy.require("https://user:pass@github.com/a", "manifestUrl"))
                .isInstanceOf(InvalidManifestException.class);
        assertThatThrownBy(() -> policy.require("https://github.com:8443/a", "manifestUrl"))
                .isInstanceOf(InvalidManifestException.class);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> policy.require("not a url", "manifestUrl"))
                .isInstanceOf(InvalidManifestException.class);
    }

    @Test
    void turnsGithubBlobPageIntoRawFile() {
        URI blob = URI.create("https://github.com/ttg/system-pf2e/blob/main/system.json");

        assertThat(policy.toRawFile(blob))
                .isEqualTo(URI.create("https://raw.githubusercontent.com/ttg/system-pf2e/main/system.json"));
    }

    @Test
    void derivesGithubRepositoryFromFileLink() {
        URI repository = URI.create("https://github.com/ttg/system-pf2e");

        assertThat(policy.repositoryOf(
                URI.create("https://raw.githubusercontent.com/ttg/system-pf2e/main/system.json"), "manifestUrl"))
                .isEqualTo(repository);
        assertThat(policy.repositoryOf(
                URI.create("https://github.com/ttg/system-pf2e/releases/latest/download/system.json"), "manifestUrl"))
                .isEqualTo(repository);
    }

    @Test
    void derivesGitlabRepositoryWithNestedGroups() {
        assertThat(policy.repositoryOf(
                URI.create("https://gitlab.com/group/sub/system/-/raw/main/system.json"), "manifestUrl"))
                .isEqualTo(URI.create("https://gitlab.com/group/sub/system"));
    }

    @Test
    void refusesLinksWithoutRepository() {
        assertThatThrownBy(() -> policy.repositoryOf(URI.create("https://github.com/ttg"), "manifestUrl"))
                .isInstanceOf(InvalidManifestException.class);
        assertThatThrownBy(() -> policy.repositoryOf(URI.create("https://github.com/ttg/system.json"), "manifestUrl"))
                .isInstanceOf(InvalidManifestException.class);
        // У GitLab без «/-/» не понять, где кончается путь репозитория.
        assertThatThrownBy(() -> policy.repositoryOf(
                URI.create("https://gitlab.com/group/system/raw/main/system.json"), "manifestUrl"))
                .isInstanceOf(InvalidManifestException.class);
    }

    @Test
    void acceptsFilesInsideRepository() {
        URI repository = URI.create("https://github.com/ttg/system-pf2e");

        policy.requireInsideRepository(repository,
                URI.create("https://raw.githubusercontent.com/ttg/system-pf2e/main/system.json"), "manifestUrl");
        policy.requireInsideRepository(repository,
                URI.create("https://github.com/ttg/system-pf2e/releases/download/v1/m.zip"), "download");
        policy.requireInsideRepository(URI.create("https://gitlab.com/group/system"),
                URI.create("https://gitlab.com/group/system/-/raw/main/system.json"), "manifestUrl");
    }

    @Test
    void githubRepositoryPathIgnoresCase() {
        // Так в манифесте dnd5e-2014: ссылка автора с ttg-club, поле manifest — с TTG-Club.
        policy.requireInsideRepository(URI.create("https://github.com/ttg-club/vttg-system-dnd5e-2014"),
                URI.create("https://github.com/TTG-Club/vttg-system-dnd5e-2014/releases/latest/download/system.json"),
                "manifest");

        assertThatThrownBy(() -> policy.requireInsideRepository(URI.create("https://gitlab.com/Group/system"),
                URI.create("https://gitlab.com/group/system/-/raw/main/system.json"), "manifest"))
                .isInstanceOf(InvalidManifestException.class);
    }

    @Test
    void rejectsFilesOutsideRepository() {
        URI repository = URI.create("https://github.com/ttg/system-pf2e");

        assertThatThrownBy(() -> policy.requireInsideRepository(repository,
                URI.create("https://github.com/evil/other/releases/download/v1/m.zip"), "download"))
                .isInstanceOf(InvalidManifestException.class)
                .hasMessageContaining("download");
        // Совпадение по началу имени — не тот же репозиторий.
        assertThatThrownBy(() -> policy.requireInsideRepository(repository,
                URI.create("https://github.com/ttg/system-pf2e-fork/raw/main/m.zip"), "download"))
                .isInstanceOf(InvalidManifestException.class);
        assertThatThrownBy(() -> policy.requireInsideRepository(repository,
                URI.create("https://gitlab.com/ttg/system-pf2e/-/raw/main/m.zip"), "download"))
                .isInstanceOf(InvalidManifestException.class);
        assertThatThrownBy(() -> policy.requireInsideRepository(repository,
                URI.create("https://github.com/ttg/system-pf2e/../other/m.zip"), "download"))
                .isInstanceOf(InvalidManifestException.class);
    }

    @Test
    void leavesOtherLinksAsIs() {
        URI release = URI.create("https://github.com/ttg/system-pf2e/releases/latest/download/system.json");

        assertThat(policy.toRawFile(release)).isEqualTo(release);
    }

    @Test
    void redirectHostIsAcceptedOnlyAsRedirect() {
        String asset = "https://release-assets.githubusercontent.com/github-production-release-asset/1/system.json";

        assertThat(policy.requireRedirect(asset, "manifestUrl")).isEqualTo(URI.create(asset));
        assertThatThrownBy(() -> policy.require(asset, "manifestUrl"))
                .isInstanceOf(InvalidManifestException.class);
        assertThatThrownBy(() -> policy.requireRedirect("https://evil.example/system.json", "manifestUrl"))
                .isInstanceOf(InvalidManifestException.class);
    }

    @Test
    void releaseAssetLinkPointsToItsRepository() {
        URI manifest = URI.create("https://github.com/TTG-Club/vttg-system-pf2e/releases/latest/download/system.json");

        assertThat(policy.repositoryOf(manifest, "manifestUrl"))
                .isEqualTo(URI.create("https://github.com/TTG-Club/vttg-system-pf2e"));
    }
}
