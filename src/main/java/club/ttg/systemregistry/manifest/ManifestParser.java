package club.ttg.systemregistry.manifest;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Разбирает {@code system.json} так же, как VTTG при установке по ссылке
 * ({@code remoteSystemInstaller.ts}): обязательны {@code id}, {@code name}
 * (или {@code title}, как у Foundry) и {@code version}.
 *
 * Реестру нужен ещё {@code download} — архив, который VTTG скачает при
 * установке из каталога. Поле {@code manifest} (канал обновлений) не
 * обязательно, но если есть — должно быть допустимой ссылкой: по нему VTTG
 * потом сам перечитывает манифест.
 */
@Component
public class ManifestParser {

    /** Он же имя папки системы на сервере VTTG. */
    static final Pattern SYSTEM_ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");

    private final ObjectMapper objectMapper;
    private final UrlPolicy urlPolicy;

    public ManifestParser(ObjectMapper objectMapper, UrlPolicy urlPolicy) {
        this.objectMapper = objectMapper;
        this.urlPolicy = urlPolicy;
    }

    public SystemManifest parse(String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JacksonException exception) {
            throw new InvalidManifestException("system.json не разобран как JSON");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidManifestException("system.json должен быть JSON-объектом");
        }

        String id = requiredString(root, "id", 64);
        if (!SYSTEM_ID.matcher(id).matches()) {
            throw new InvalidManifestException(
                    "id: только строчные латинские буквы, цифры, «-» и «_», до 64 символов");
        }

        // `title` — алиас `name`, как в манифестах Foundry.
        String name = optionalString(root, "name", 150);
        if (name == null) {
            name = optionalString(root, "title", 150);
        }
        if (name == null) {
            throw new InvalidManifestException("name: обязательное поле");
        }

        String version = requiredString(root, "version", 50);
        if (!Versions.SEMANTIC.matcher(version).matches()) {
            throw new InvalidManifestException("version: нужна семантическая версия, например 1.0.0");
        }

        String download = requiredString(root, "download", 2048);
        urlPolicy.require(download, "download");

        String updateManifest = optionalString(root, "manifest", 2048);
        if (updateManifest != null) {
            updateManifest = urlPolicy.toRawFile(urlPolicy.require(updateManifest, "manifest")).toString();
        }

        return new SystemManifest(
                id,
                name,
                version,
                optionalString(root, "description", 2000),
                author(root),
                compatibility(root),
                download,
                updateManifest,
                json
        );
    }

    /** {@code authors: [{name, url}]} по контракту VTTG; {@code author} — старый вариант Foundry. */
    private static String author(JsonNode root) {
        JsonNode authors = root.get("authors");
        if (authors != null && !authors.isNull()) {
            if (!authors.isArray()) {
                throw new InvalidManifestException("authors: нужен массив объектов {name, url}");
            }
            List<String> names = new ArrayList<>();
            for (JsonNode entry : authors) {
                if (!entry.isObject()) {
                    throw new InvalidManifestException("authors: нужен массив объектов {name, url}");
                }
                String name = optionalString(entry, "name", 150);
                if (name != null && !names.contains(name)) {
                    names.add(name);
                }
            }
            if (!names.isEmpty()) {
                String joined = String.join(", ", names);
                if (joined.length() > 150) {
                    throw new InvalidManifestException("authors: имена вместе длиннее 150 символов");
                }
                return joined;
            }
        }
        return optionalString(root, "author", 150);
    }

    private static SystemManifest.Compatibility compatibility(JsonNode root) {
        JsonNode node = root.get("compatibility");
        if (node == null || node.isNull()) {
            return SystemManifest.Compatibility.NONE;
        }
        if (!node.isObject()) {
            throw new InvalidManifestException("compatibility: нужен объект {minimum, verified, maximum}");
        }

        String minimum = vttgVersion(node, "minimum");
        String verified = vttgVersion(node, "verified");
        String maximum = vttgVersion(node, "maximum");
        if (minimum != null && maximum != null && Versions.compare(minimum, maximum) > 0) {
            throw new InvalidManifestException("compatibility: minimum больше maximum");
        }
        return new SystemManifest.Compatibility(minimum, verified, maximum);
    }

    private static String vttgVersion(JsonNode compatibility, String field) {
        String value = optionalString(compatibility, field, 50);
        if (value != null && !Versions.LOOSE.matcher(value).matches()) {
            throw new InvalidManifestException("compatibility." + field + ": нужна версия VTTG, например 0.9.266");
        }
        return value;
    }

    private static String requiredString(JsonNode root, String field, int maxLength) {
        String value = optionalString(root, field, maxLength);
        if (value == null) {
            throw new InvalidManifestException(field + ": обязательное поле");
        }
        return value;
    }

    private static String optionalString(JsonNode root, String field, int maxLength) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw new InvalidManifestException(field + ": нужна строка");
        }

        String value = node.stringValue().strip();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > maxLength) {
            throw new InvalidManifestException(field + ": длиннее " + maxLength + " символов");
        }
        return value;
    }
}
