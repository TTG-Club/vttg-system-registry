package club.ttg.systemregistry.submission;

import club.ttg.systemregistry.manifest.ManifestFetcher;
import club.ttg.systemregistry.manifest.SystemManifest;
import club.ttg.systemregistry.manifest.UrlPolicy;
import club.ttg.systemregistry.submission.api.SubmissionRequest;
import club.ttg.systemregistry.submission.api.SubmissionResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Заявки авторов и решения модераторов.
 *
 * Манифест скачивается вне транзакции: чужой сервер может отвечать до
 * таймаута, и держать всё это время соединение с базой незачем. Поэтому
 * методы с загрузкой проверяют заявку дважды — до загрузки и в транзакции
 * записи; гонку правок ловит {@code @Version}.
 */
@Service
public class SubmissionService {

    private final SystemSubmissionRepository repository;
    private final ManifestFetcher manifestFetcher;
    private final UrlPolicy urlPolicy;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;
    private final Clock clock;

    public SubmissionService(
            SystemSubmissionRepository repository,
            ManifestFetcher manifestFetcher,
            UrlPolicy urlPolicy,
            TransactionTemplate transaction,
            Clock clock
    ) {
        this.repository = repository;
        this.manifestFetcher = manifestFetcher;
        this.urlPolicy = urlPolicy;
        this.transaction = transaction;
        this.readOnly = new TransactionTemplate(transaction.getTransactionManager());
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    public SubmissionResponse submit(UUID authorId, String authorName, SubmissionRequest request) {
        Prepared prepared = prepare(request);

        return transaction.execute(status -> {
            requireSystemIdFree(prepared.manifest().id(), authorId, null);

            SystemSubmission submission = SystemSubmission.submit(
                    authorId,
                    authorName,
                    prepared.repositoryUrl(),
                    prepared.manifestUrl(),
                    request.description(),
                    prepared.manifest(),
                    clock.instant()
            );
            return SubmissionResponse.from(repository.save(submission));
        });
    }

    public SubmissionResponse resubmit(UUID id, UUID userId, SubmissionRequest request) {
        readOnly.executeWithoutResult(status -> requireOwned(id, userId));
        Prepared prepared = prepare(request);

        return transaction.execute(status -> {
            SystemSubmission submission = requireOwned(id, userId);
            requireSystemIdFree(prepared.manifest().id(), userId, id);

            submission.resubmit(
                    prepared.repositoryUrl(),
                    prepared.manifestUrl(),
                    request.description(),
                    prepared.manifest(),
                    clock.instant()
            );
            return SubmissionResponse.from(submission);
        });
    }

    /**
     * Перечитывает {@code system.json} по сохранённой ссылке: новая версия и
     * архив без повторной модерации. Архив и канал обновлений по-прежнему
     * обязаны лежать в том же репозитории, так что сменить источник системы
     * так нельзя.
     */
    public SubmissionResponse refreshManifest(UUID id, UUID userId) {
        SystemSubmission stored = readOnly.execute(status -> requireOwned(id, userId));
        SystemManifest manifest = fetchFromRepository(
                URI.create(stored.getRepositoryUrl()), URI.create(stored.getManifestUrl()));

        return transaction.execute(status -> {
            SystemSubmission submission = requireOwned(id, userId);
            submission.refreshManifest(manifest, clock.instant());
            return SubmissionResponse.from(submission);
        });
    }

    public void withdraw(UUID id, UUID userId) {
        transaction.executeWithoutResult(status -> requireOwned(id, userId).withdraw(clock.instant()));
    }

    public List<SubmissionResponse> findMine(UUID userId) {
        return readOnly.execute(status -> repository.findByAuthorIdOrderByCreatedAtDesc(userId).stream()
                .map(SubmissionResponse::from)
                .toList());
    }

    /** Автор видит свою заявку, модератор — любую; остальным её как будто нет. */
    public SubmissionResponse findVisible(UUID id, UUID userId, boolean moderator) {
        return readOnly.execute(status -> {
            SystemSubmission submission = repository.findById(id)
                    .filter(found -> moderator || found.isOwnedBy(userId))
                    .orElseThrow(() -> new SubmissionNotFoundException(id));
            return SubmissionResponse.from(submission);
        });
    }

    /** Очередь модерации: заявки с любым из статусов; пустой список — все заявки. */
    public Page<SubmissionResponse> findForModeration(Collection<SubmissionStatus> statuses, Pageable pageable) {
        return readOnly.execute(tx -> (statuses == null || statuses.isEmpty()
                ? repository.findAll(pageable)
                : repository.findByStatusIn(statuses, pageable))
                .map(SubmissionResponse::from));
    }

    /**
     * Одобряет заявку. Если у системы уже есть одобренная заявка того же
     * автора, новая её заменяет: прежняя уходит из каталога со статусом
     * {@link SubmissionStatus#SUPERSEDED}.
     */
    public SubmissionResponse approve(UUID id, UUID moderatorId, String comment) {
        return transaction.execute(status -> {
            SystemSubmission submission = require(id);
            Instant now = clock.instant();

            repository.findApprovedBySystemId(submission.getSystemId())
                    .filter(approved -> !approved.getId().equals(id))
                    .ifPresent(approved -> {
                        if (!approved.isOwnedBy(submission.getAuthorId())) {
                            throw new SystemIdTakenException("Система с id «" + submission.getSystemId()
                                    + "» уже зарегистрирована другим автором");
                        }
                        approved.supersede(now);
                        // Прежняя должна уйти из одобренных до того, как одобрим
                        // новую: иначе сработает уникальный индекс по id системы.
                        repository.saveAndFlush(approved);
                    });

            submission.approve(moderatorId, comment, now);
            return SubmissionResponse.from(submission);
        });
    }

    public SubmissionResponse reject(UUID id, UUID moderatorId, String comment) {
        return transaction.execute(status -> {
            SystemSubmission submission = require(id);
            submission.reject(moderatorId, comment, clock.instant());
            return SubmissionResponse.from(submission);
        });
    }

    /** Проверки без базы и загрузка манифеста — до транзакции. */
    private Prepared prepare(SubmissionRequest request) {
        URI manifestUrl = manifestFetcher.resolveManifestUrl(request.manifestUrl());
        // Репозиторий не спрашиваем у автора, а берём из ссылки на манифест.
        URI repositoryUrl = urlPolicy.repositoryOf(manifestUrl, "manifestUrl");
        SystemManifest manifest = fetchFromRepository(repositoryUrl, manifestUrl);

        return new Prepared(repositoryUrl.toString(), manifestUrl.toString(), manifest);
    }

    /**
     * Архив системы и канал обновлений ({@code manifest}) должны лежать в
     * заявленном репозитории: по каналу VTTG сам перечитывает манифест, и
     * ссылка «наружу» обошла бы модерацию.
     */
    private SystemManifest fetchFromRepository(URI repositoryUrl, URI manifestUrl) {
        SystemManifest manifest = manifestFetcher.fetch(manifestUrl);
        urlPolicy.requireInsideRepository(repositoryUrl, URI.create(manifest.download()), "download");
        if (manifest.updateManifest() != null) {
            urlPolicy.requireInsideRepository(repositoryUrl, URI.create(manifest.updateManifest()), "manifest");
        }
        return manifest;
    }

    /**
     * Чужую систему занять нельзя. Своей одобренной системе автор может
     * подать одну заявку-замену (например, со сменой ссылок), но вторую
     * заявку на рассмотрении — нет: её нужно править.
     */
    private void requireSystemIdFree(String systemId, UUID authorId, UUID exceptSubmissionId) {
        for (SystemSubmission live : repository.findLiveBySystemId(systemId)) {
            if (live.getId().equals(exceptSubmissionId)) {
                continue;
            }
            if (!live.isOwnedBy(authorId)) {
                throw new SystemIdTakenException(
                        "Система с id «" + systemId + "» уже зарегистрирована другим автором");
            }
            if (live.getStatus() == SubmissionStatus.PENDING) {
                throw new SystemIdTakenException(
                        "У вас уже есть заявка на систему «" + systemId + "» на рассмотрении — правьте её");
            }
        }
    }

    private SystemSubmission requireOwned(UUID id, UUID userId) {
        return repository.findById(id)
                .filter(submission -> submission.isOwnedBy(userId))
                .orElseThrow(() -> new SubmissionNotFoundException(id));
    }

    private SystemSubmission require(UUID id) {
        return repository.findById(id).orElseThrow(() -> new SubmissionNotFoundException(id));
    }

    private record Prepared(String repositoryUrl, String manifestUrl, SystemManifest manifest) {
    }
}
