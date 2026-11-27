package club.ttg.systemregistry.submission.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Заявка автора. Репозиторий отдельно не указывается: сервис берёт его из
 * ссылки на манифест.
 *
 * @param manifestUrl ссылка на {@code system.json} в открытом репозитории
 *                    GitHub или GitLab (файл в ветке или ассет релиза);
 *                    страницу файла на GitHub сервис сам заменит на «сырой» файл
 * @param description описание для каталога; без него берётся {@code description}
 *                    из манифеста
 */
public record SubmissionRequest(
        @NotBlank @Size(max = 2048) String manifestUrl,
        @Size(max = 1000) String description
) {
}
