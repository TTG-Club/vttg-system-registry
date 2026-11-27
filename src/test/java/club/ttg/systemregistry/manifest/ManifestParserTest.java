package club.ttg.systemregistry.manifest;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestParserTest {

    private static final String DOWNLOAD = "https://github.com/a/b/releases/download/v1/s.zip";

    private final ManifestParser parser = new ManifestParser(
            JsonMapper.builder().build(),
            new UrlPolicy(UrlPolicyTest.properties()));

    @Test
    void parsesVttgSystemManifest() {
        SystemManifest manifest = parser.parse("""
                {
                  "id": "pf2e",
                  "name": "Pathfinder 2e (Remaster)",
                  "version": "0.1.0",
                  "description": "Pathfinder Second Edition",
                  "authors": [{"name": "TTG Club", "url": "https://ttg.club"}, {"name": "Гость"}, {"name": "TTG Club"}],
                  "compatibility": {"minimum": "0.9.602", "verified": "0.9.602"},
                  "url": "https://github.com/TTG-Club/vttg-system-pf2e",
                  "manifest": "https://github.com/TTG-Club/vttg-system-pf2e/releases/latest/download/system.json",
                  "download": "https://github.com/TTG-Club/vttg-system-pf2e/releases/download/v0.1.0/pf2e.zip",
                  "client": {"entry": "client.js"}
                }
                """);

        assertThat(manifest.id()).isEqualTo("pf2e");
        assertThat(manifest.name()).isEqualTo("Pathfinder 2e (Remaster)");
        assertThat(manifest.author()).isEqualTo("TTG Club, Гость");
        assertThat(manifest.description()).isEqualTo("Pathfinder Second Edition");
        assertThat(manifest.compatibility())
                .isEqualTo(new SystemManifest.Compatibility("0.9.602", "0.9.602", null));
        assertThat(manifest.updateManifest()).endsWith("/releases/latest/download/system.json");
        assertThat(manifest.json()).contains("client.js");
    }

    @Test
    void acceptsTitleAndLegacyAuthor() {
        SystemManifest manifest = parser.parse(manifest("\"title\": \"Система\", \"author\": \"Автор\""));

        assertThat(manifest.name()).isEqualTo("Система");
        assertThat(manifest.author()).isEqualTo("Автор");
        assertThat(manifest.compatibility()).isEqualTo(SystemManifest.Compatibility.NONE);
        assertThat(manifest.updateManifest()).isNull();
    }

    @Test
    void githubBlobUpdateManifestBecomesRawFile() {
        SystemManifest manifest = parser.parse(manifest(
                "\"name\": \"С\", \"manifest\": \"https://github.com/a/b/blob/main/system.json\""));

        assertThat(manifest.updateManifest()).isEqualTo("https://raw.githubusercontent.com/a/b/main/system.json");
    }

    @Test
    void requiresDownload() {
        assertThatThrownBy(() -> parser.parse("""
                {"id": "s", "name": "С", "version": "1.0.0"}
                """))
                .isInstanceOf(InvalidManifestException.class)
                .hasMessageContaining("download");
    }

    @Test
    void rejectsLinksOnForeignHost() {
        assertThatThrownBy(() -> parser.parse("""
                {"id": "s", "name": "С", "version": "1.0.0", "download": "https://evil.example/s.zip"}
                """))
                .isInstanceOf(InvalidManifestException.class)
                .hasMessageContaining("download");
        assertThatThrownBy(() -> parser.parse(manifest(
                "\"name\": \"С\", \"manifest\": \"https://evil.example/system.json\"")))
                .isInstanceOf(InvalidManifestException.class)
                .hasMessageContaining("manifest");
    }

    @Test
    void rejectsIdUnsafeForFolderName() {
        assertThatThrownBy(() -> parser.parse("""
                {"id": "../etc", "name": "С", "version": "1.0.0", "download": "%s"}
                """.formatted(DOWNLOAD)))
                .isInstanceOf(InvalidManifestException.class)
                .hasMessageContaining("id");
    }

    @Test
    void rejectsNonSemanticVersion() {
        assertThatThrownBy(() -> parser.parse("""
                {"id": "s", "name": "С", "version": "latest", "download": "%s"}
                """.formatted(DOWNLOAD)))
                .isInstanceOf(InvalidManifestException.class)
                .hasMessageContaining("version");
    }

    @Test
    void validatesCompatibility() {
        assertThatThrownBy(() -> parser.parse(manifest(
                "\"name\": \"С\", \"compatibility\": {\"minimum\": \"новая\"}")))
                .isInstanceOf(InvalidManifestException.class)
                .hasMessageContaining("compatibility.minimum");
        assertThatThrownBy(() -> parser.parse(manifest(
                "\"name\": \"С\", \"compatibility\": {\"minimum\": \"0.10\", \"maximum\": \"0.9.900\"}")))
                .isInstanceOf(InvalidManifestException.class)
                .hasMessageContaining("minimum больше maximum");
    }

    @Test
    void rejectsNonObjectJson() {
        assertThatThrownBy(() -> parser.parse("[1, 2]"))
                .isInstanceOf(InvalidManifestException.class);
        assertThatThrownBy(() -> parser.parse("<html>"))
                .isInstanceOf(InvalidManifestException.class);
    }

    private static String manifest(String fields) {
        return "{\"id\": \"s\", \"version\": \"1.0.0\", \"download\": \"" + DOWNLOAD + "\", " + fields + "}";
    }
}
