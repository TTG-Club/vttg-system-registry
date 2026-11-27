# VTTG System Registry

Реестр игровых систем виртуального стола VTTG. Авторы подают заявку на свою
систему (ссылка на `system.json` в открытом репозитории), администратор или
модератор одобряет или отклоняет её с комментарием, VTTG забирает каталог
одобренных систем.

Устроен так же, как [vttg-module-registry](https://github.com/TTG-Club/vttg-module-registry):
Spring Boot 4 / Java 21 / PostgreSQL / Liquibase. Авторизация — JWT из
`auth-service` (тот же секрет, что у остальных сервисов), роли из claim `roles`.

## Запуск

```bash
docker compose up -d          # Postgres на localhost:5434
AUTH_SERVICE_JWT_SECRET=... ./mvnw spring-boot:run
```

Swagger: `http://localhost:8080/swagger-ui.html`.

| Переменная | По умолчанию | |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5434/vttg_systems`, `vttg_systems` | |
| `AUTH_SERVICE_JWT_SECRET` | — | обязательна, ≥ 32 байт |
| `MANIFEST_ALLOWED_HOSTS` | github.com, raw.githubusercontent.com, gitlab.com | где могут лежать системы: хосты открытых репозиториев через запятую (свой GitLab добавляется сюда же) |
| `MANIFEST_REDIRECT_HOSTS` | objects.githubusercontent.com, release-assets.githubusercontent.com | куда разрешённый хост может переадресовать загрузку манифеста (ассеты релизов GitHub); как ссылку автора их указать нельзя |
| `MANIFEST_MAX_SIZE` / `MANIFEST_TIMEOUT` | `256KB` / `10s` | |
| `CORS_ALLOWED_ORIGINS` | https://new.ttg.club, https://ttg.club, https://dev.ttg.club | сайт с формой заявки и модерацией |

> **Windows и не-ASCII путь к `%TEMP%`.** `HttpClient` JDK создаёт Unix-сокет во
> временной папке и падает с `Unable to establish loopback connection`, если в пути
> есть кириллица. Локально запускайте с `-Djdk.net.unixdomain.tmpdir=D:/tmp`
> (для тестов: `./mvnw test "-DargLine=-Djdk.net.unixdomain.tmpdir=D:/tmp"`).
> В Docker-образе проблемы нет.

## Требования к системе

`system.json` по контракту VTTG (`vttg/docs/SYSTEMS.md`,
`remoteSystemInstaller.ts`), в котором обязательно поле `download`:

```json
{
  "id": "pf2e",
  "name": "Pathfinder 2e (Remaster)",
  "version": "0.1.0",
  "description": "Pathfinder Second Edition по правилам Remaster",
  "authors": [{ "name": "TTG Club", "url": "https://ttg.club" }],
  "compatibility": { "minimum": "0.9.602", "verified": "0.9.602" },
  "manifest": "https://github.com/<owner>/<repo>/releases/latest/download/system.json",
  "download": "https://github.com/<owner>/<repo>/releases/download/v0.1.0/pf2e.zip"
}
```

- `id`: `^[a-z0-9][a-z0-9_-]{0,63}$` (он же имя папки системы на сервере VTTG);
- `name`: можно `title`, как у Foundry;
- `version`: семантическая версия;
- `download`: https-ссылка на архив этой версии в том же репозитории
  (например, на ассет релиза GitHub);
- `manifest` (канал обновлений, необязательно): если есть, тоже должен лежать
  в том же репозитории. VTTG сам перечитывает манифест по этой ссылке, так
  что ссылка «наружу» обошла бы модерацию;
- `compatibility.minimum` / `maximum`: версии VTTG; по ним каталог фильтруется
  параметром `vttgVersion`;
- `description`: идёт в каталог, если автор не написал описание в заявке.
  Нужно хотя бы одно из двух.

Ссылки на манифест принимаются и на файл в ветке, и на ассет релиза
(`…/releases/latest/download/system.json`): переадресацию GitHub в хранилище
ассетов сервис проходит сам, проверяя каждый шаг. Ссылку на страницу файла
GitHub (`…/blob/<ref>/system.json`) сервис сам заменяет на
`raw.githubusercontent.com`.

Репозиторий автор отдельно не указывает: сервис берёт его из ссылки на
`system.json`. У GitHub это первые два сегмента пути (`владелец/репозиторий`,
без учёта регистра), у GitLab это путь до служебного `/-/`.

Сервис скачивает манифест при подаче, при правке и по кнопке «Перечитать
манифест». Переадресации проверяются по спискам хостов, адреса внутренних
сетей отклоняются.

## Жизненный цикл заявки

```
PENDING ──approve──▶ APPROVED ──reject (снятие из каталога)──▶ REJECTED
PENDING ──reject───▶ REJECTED ──правка автором──▶ PENDING
APPROVED ──одобрена заявка-замена того же автора──▶ SUPERSEDED
любой, кроме WITHDRAWN и SUPERSEDED ──withdraw──▶ WITHDRAWN
```

- **Первое одобрение фиксирует ссылку на `system.json`**, а с ней и
  репозиторий. Это действует, даже если систему потом сняли из каталога.
  Чтобы сменить ссылку, автор подаёт новую заявку на ту же систему. Старая
  заявка остаётся в каталоге, пока новую не одобрят, а после одобрения
  получает статус `SUPERSEDED`.
- Чужой `id` системы занять нельзя. У своей системы может быть не больше
  одной одобренной заявки и одной на рассмотрении.
- Отклонение требует комментария.
- Новая версия системы подхватывается перечитыванием манифеста без повторной
  модерации. `id` системы менять нельзя, архив и канал обновлений по-прежнему
  должны лежать в том же репозитории.

## API

| Метод | Путь | Доступ | |
|---|---|---|---|
| GET | `/api/v1/systems?vttgVersion=<x.y.z>` | все | каталог одобренных; с `vttgVersion` — только совместимые |
| GET | `/api/v1/systems/{systemId}` | все | одна система каталога |
| POST | `/api/v1/submissions` | вошедший | подать заявку `{manifestUrl, description?}` |
| GET | `/api/v1/submissions/my` | вошедший | мои заявки |
| GET | `/api/v1/submissions/{id}` | автор / модератор | заявка |
| PUT | `/api/v1/submissions/{id}` | автор | исправить и отправить заново |
| POST | `/api/v1/submissions/{id}/refresh-manifest` | автор | перечитать `system.json` |
| DELETE | `/api/v1/submissions/{id}` | автор | отозвать |
| GET | `/api/v1/moderation/submissions?status=PENDING` | ADMIN, MODERATOR | очередь (страницы), статусы через запятую |
| POST | `/api/v1/moderation/submissions/{id}/approve` | ADMIN, MODERATOR | одобрить, `{comment?}` |
| POST | `/api/v1/moderation/submissions/{id}/reject` | ADMIN, MODERATOR | отклонить / снять, `{comment}` |

Ошибки отдаются как `application/problem+json`:

- 422: манифест или ссылка не прошли проверку;
- 502: манифест не скачался;
- 409: `id` занят или действие недоступно в текущем статусе;
- 400: некорректный параметр (например, `vttgVersion`).

### Ответ каталога (для VTTG)

```json
[{
  "id": "pf2e",
  "name": "Pathfinder 2e (Remaster)",
  "version": "0.1.0",
  "description": "Описание из заявки или из system.json",
  "author": "TTG Club",
  "compatibility": { "minimum": "0.9.602", "verified": "0.9.602", "maximum": null },
  "repositoryUrl": "https://github.com/…",
  "manifestUrl": "https://github.com/…/releases/latest/download/system.json",
  "downloadUrl": "https://github.com/…/pf2e.zip",
  "approvedAt": "2026-10-07T10:00:00Z",
  "updatedAt": "2026-10-07T10:00:00Z"
}]
```

`version` и `downloadUrl` — снимок на момент последней синхронизации. При
установке VTTG передаёт `manifestUrl` в `remoteSystemInstaller.ts` и читает
актуальный манифест, как при установке по ссылке.

Этот же каталог может служить справочником систем для
`vttg-module-registry` (сейчас там своя таблица `game_systems`).

## Деплой

`.github/workflows/deploy.yml` работает как у остальных сервисов: пуш в
`main` или `dev` собирает образ через `TTG-Club/shared-workflows/standard@v1`
и выкатывает его в Dokploy (`main` → прод, `dev` → стенд). В репозитории
нужны секреты `DOKPLOY_APP_ID_PROD`, `DOKPLOY_APP_ID_DEV`, `DOKPLOY_URL`,
`DOKPLOY_API_KEY`, `REGISTRY_URL`, `REGISTRY_USERNAME`, `REGISTRY_PASSWORD`,
`COSIGN_PRIVATE_KEY`.
