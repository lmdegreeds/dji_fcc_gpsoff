# Project rules — DJI_FCC_GPSOFF

## Bump the build number on EVERY build

Before producing any APK — debug or release — increment `versionCode` and update
`versionName` in `app/build.gradle.kts`. No exceptions, no "it's just a debug
build".

**Why this rule exists.** On 2026-08-18 half an hour was lost arguing about
whether a fix was live on the controller. The evidence was contradictory: the
diag endpoints matched a new build, the frame timing in the log matched an old
one, and the user had installed *a* build but not the latest. Neither side could
prove anything because nothing in the app said which build it was. A version
number visible in the log settles that in one request.

The version must be visible at all three stages, or the rule is pointless:

| Stage | Where it shows |
|---|---|
| file on disk | APK is named `dji-fcc-gpsoff-<versionName>-<buildType>.apk` |
| app running | first `DiagLog` line: `build v1.0.1 (code 2) starting` |
| live controller | `GET /version` on the diag server |

All three are wired in `app/build.gradle.kts` (output naming), `App.onCreate`
(startup line) and `DiagServer` (`/version`). Keep them wired.

Ask before a MAJOR/MINOR change; `versionCode` and the patch part are yours to
bump freely.

## Verifying on hardware

The controller is reachable over LAN at the address the user gives (`:8899`).
Before drawing any conclusion from a live log, confirm the running build first:

    curl -s http://<rc>:8899/version

Only then interpret the log.

## Date every documentation change

Any new or edited section under `doc/` carries the date it was written, in the
heading or the first line: `## Что-то (2026-08-19)` or `**Замерено 2026-08-19.**`

Hardware findings age. A measurement is tied to a firmware version, a build and a
controller state, and six months later the only way to judge whether a claim still
holds is to know when it was made. An undated finding is indistinguishable from a
current one, and this project's docs are almost entirely findings.

The same applies to a correction: when a claim is retracted, date the retraction
too, so the reader can see which of two contradicting statements is the later one.

## Пока идёт разработка — коммить только в `dev` (2026-08-19)

Все изменения идут в ветку `dev`. В `main` ничего не коммитить и не сливать
самостоятельно — слияние и выпуск релиза делаются **только по явной команде
пользователя**.

Причина: на пульте стоит рабочая сборка, которой пользуются, и ветка `main`
должна оставаться тем, к чему можно откатиться в любой момент. Сборки для
проверки на железе собираются из `dev` и ставятся обновлением поверх.

Если работа начата не в `dev` — переключиться на неё до первого коммита:

    git checkout dev        # или: git checkout -b dev

Правило про номер сборки при этом никуда не девается: `versionCode` и
`versionName` поднимаются на каждой сборке, включая сборки из `dev`.

## Публикация: только ключ из `keystore.properties` (2026-08-19)

В GitHub Releases выкладываются ТОЛЬКО сборки, подписанные настоящим ключом.
Тестовые — тоже настоящим ключом, но релиз помечается `--prerelease`: тогда
галочка «включая предварительные» в Настройках действительно управляет тем, что
увидит пользователь.

Debug-сборки не публикуются никогда. Их подписывает `~/.android/debug.keystore`,
он свой у каждой машины, а Android не даёт заменить приложение сборкой с другой
подписью. Пользователь такого APK попадает в тупик: обновление скачается и не
поставится, а перейти можно только удалив приложение вместе с настройками.

Перед публикацией сверить отпечаток — Gradle печатает его на каждой release-сборке:

    release signing: keystore.properties → dji-fcc-gpsoff (fingerprint 46435df3)

Он же попадает в имя файла (`dji-fcc-gpsoff-1.1.1-release-46435df3.apk`), и по нему
приложение отбирает ассеты при проверке обновлений (`AppSignature`,
`Updater.pickAsset`). Если в строке сказано «NO keystore.properties — falling back
to the DEBUG key», этот APK публиковать нельзя.
