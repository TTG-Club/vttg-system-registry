package club.ttg.systemregistry.manifest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Где и как сервис читает {@code system.json}.
 *
 * @param allowedHosts  хосты, на которые могут указывать ссылки на манифест и
 *                      архив системы (точное совпадение, без поддоменов)
 * @param redirectHosts хосты, куда разрешённый хост может переадресовать
 *                      загрузку манифеста — например, хранилище ассетов
 *                      релизов GitHub для ссылок {@code …/releases/latest/download/system.json}.
 *                      Как ссылку автора их указать нельзя
 * @param maxSize       предельный размер манифеста
 * @param timeout       таймаут соединения и чтения
 * @param maxRedirects  сколько переадресаций пройти; каждая проверяется заново
 */
@ConfigurationProperties(prefix = "manifest")
public record ManifestProperties(
        List<String> allowedHosts,
        List<String> redirectHosts,
        DataSize maxSize,
        Duration timeout,
        Integer maxRedirects
) {

    public ManifestProperties {
        allowedHosts = normalize(allowedHosts);
        redirectHosts = normalize(redirectHosts);
        maxSize = maxSize == null ? DataSize.ofKilobytes(256) : maxSize;
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        maxRedirects = maxRedirects == null ? 3 : maxRedirects;
    }

    private static List<String> normalize(List<String> hosts) {
        return hosts == null ? List.of() : hosts.stream()
                .map(String::strip)
                .filter(host -> !host.isEmpty())
                .map(host -> host.toLowerCase(Locale.ROOT))
                .toList();
    }
}
