package club.ttg.systemregistry.manifest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionsTest {

    @Test
    void comparesNumericallyAndPadsMissingParts() {
        assertThat(Versions.compare("0.9.266", "0.9.1000")).isNegative();
        assertThat(Versions.compare("0.10", "0.9.999")).isPositive();
        assertThat(Versions.compare("1", "1.0.0")).isZero();
        assertThat(Versions.compare("1.0.0-beta", "1.0.0")).isZero();
    }

    @Test
    void compatibilityBoundsAreInclusive() {
        var range = new SystemManifest.Compatibility("0.9.266", null, "0.9.600");

        assertThat(range.accepts("0.9.266")).isTrue();
        assertThat(range.accepts("0.9.600")).isTrue();
        assertThat(range.accepts("0.9.265")).isFalse();
        assertThat(range.accepts("0.9.601")).isFalse();
        assertThat(SystemManifest.Compatibility.NONE.accepts("99.0.0")).isTrue();
    }
}
