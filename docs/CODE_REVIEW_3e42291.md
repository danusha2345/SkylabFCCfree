# Code Review — правки за 2026-07-19

**Диапазон:** `a4e4305..3e42291` (7 коммитов)
**Метод:** 8 finder-углов (построчный / удалённое поведение / кросс-файловый / reuse / simplification / efficiency / altitude / conventions), затем адверсариальная верификация каждого корректностного кандидата (по 1–2 верификатора).
**Сборка:** зелёная, все 12 тест-классов проходят.

## Контекст

Главная правка дня — замена периодического keepalive-цикла (переприменение FCC каждые 2с) на **one-shot модель**: foreground-сервис пассивно слушает Home Point (`03:44`) на порту 40007 по одному долгоживущему соединению и применяет полный FCC-профиль один раз после записи точки возврата, затем останавливается сам. Добавлены `HomePointMonitor`, `Port40007Lock`, `LedReadback`, `FccRuntime`; удалён `FccKeepaliveSchedule`. Переход на one-shot принёс несколько реальных проблем.

---

## Подтверждённые баги (CONFIRMED)

### 1. Гонка при завершении one-shot затирает принятый запрос Auto-FCC
**`FccKeepaliveService.kt:221-227`** · критично

Эпилог воркера (`runRequested.set(false)`, сброс `PREF_KEEPALIVE`, `stopSelf()`) выполняется в корутине **без** `synchronized(Companion)`, тогда как `start()`/`onStartCommand` держат этот монитор. Если пользователь (или LAN `auto_fcc_on`) вызывает `start()` в момент завершения записи, воркер затирает только что выставленные `runRequested=true`/pref=true обратно в false, и пришедший следом `ACTION_START` на строке 122 видит `false` → сервис молча останавливается. Принятый запрос теряется без ошибки.

**Фикс:** обернуть эпилог в `synchronized(Companion)` или проверять generation-счётчик старта перед сбросом.

### 2. Удалён retry неудачного bootstrap — FCC не применяется при разовом сбое
**`FccKeepaliveService.kt:234`** · критично

`FccKeepaliveSchedule` (и его тест `failedBootstrapIsRetriedBeforeKeepalive`) удалены, инвариант «повторить неудачную запись» не восстановлен. Теперь если `transport.connect()` вернул false или запись бросила исключение (например, DUML-брокер занят DJI Fly в момент записи Home Point) — `runRequested` и `PREF_KEEPALIVE` **безусловно** очищаются (строки 221-227), сервис останавливается в FAILED, и FCC не применяется до перезапуска приложения, хотя `auto_fcc` включён. Контеншн локов до записи ретраится бесконечно, а сам сбой записи — нет.

**Фикс:** ретраить запись с backoff, не очищая `PREF_KEEPALIVE` при транзиентном сбое.

### 3. Ветки onStartCommand останавливают сервис без startForeground() → краш
**`FccKeepaliveService.kt:122-127, 132-143, 147-153`** · критично

Сервис стартует через `startForegroundService()` (обязательство вызвать `startForeground()` за ~5с). Ветки superseded-start (122-127), null-intent sticky-restart с флагом false (134-137) и profile-missing (147-153) вызывают `stopSelfResult()`/`stopForeground()` **без** предварительного `startForeground(NOTIFICATION_ID, …)`. `stopForeground()` не снимает обязательство → на API 26+ `RemoteServiceException: did not then call Service.startForeground()`, на API 31+ `ForegroundServiceDidNotStartInTimeException`, краш процесса.

**Фикс:** вызвать `startForeground(NOTIFICATION_ID, createNotification())` перед каждым ранним stop.

### 4. exchangeWire глотает mid-stream IOException и отдаёт усечённый ответ как успех
**`DumlTransport.kt:482-488`** · важно

Внутренний read-loop ловит `IOException` (не `SocketTimeoutException`) через `break` и проваливается в успешный `return WireExchangeResult(writeCompleted=true, failureStage=null)` (строки 501-504); переменная `failureStage="read"` со строки 469 не доходит до внешнего catch. `handleLanWireExchange` (`FccViewModel.kt:1813`) различает только `!writeCompleted`, поэтому ECONNRESET посреди ответа отдаётся как **HTTP 200 ok=true** с усечённым `response_hex` — сломанный захват выглядит как полноценный.

**Фикс:** сохранять `failureStage="read"` при mid-stream IOException и отдавать отдельный признак (`truncated`/`read_error`) в LAN-ответе.

### 5. Стартовое чтение LED-состояния теряется навсегда при занятом порту
**`FccViewModel.kt:915-946`** · важно

Gate `startupLedReadAttempted` сбрасывается только когда `refreshLedState()` вернул `false` синхронно (строка 918). Но при таймауте `Port40007Lock.acquireForLed()` функция уже вернула `true` (строка 963), а падает асинхронно внутри корутины (`portLease == null` → `ledStatus="LED port busy"`, строки 941-946), не трогая флаг. В итоге все 6 последующих вызовов `refreshLedStateAfterStartup()` no-op на CAS, и карточка LED остаётся UNKNOWN до ручного refresh. Флаг нигде не сбрасывается и на реконнекте.

**Фикс:** сбрасывать `startupLedReadAttempted` в async-ветках отказа (и на disconnect).

### 6. HomePointMonitor.isRecorded не проверяет cmdType (push vs response)
**`HomePointMonitor.kt:27-48`** · важно (структурно подтверждено)

`waitUntilRecorded` **сам** шлёт ACK-probe (`buildProbe`, `cmdType=0x40`, строки 130-133), а `isRecorded` фильтрует только по sender/dst и `cmdSet:cmdId` (`03:44`, строка 41), не глядя на `frame[8]` (бит 7 = request/response). Response-кадр на probe несёт другой cmdType, но те же sender/dst/cmdSet/cmdId → проходит все фильтры и парсится по тем же фиксированным офсетам `[31]/[32]`, что и push. Если response добавляет ведущий статус-байт (стандартная DUML-конвенция), поле home-state сдвигается на 1 байт → возможен ложный «записано» и **преждевременное применение FCC**.

Не доказуемо из репозитория: что response реально prepend'ит статус-байт (в коде не закреплено). Требует подтверждения живым захватом, но проверка `frame[8]` напрашивается в любом случае.

**Фикс:** отбрасывать в `isRecorded` кадры с установленным битом 7 в `frame[8]` (принимать только push), либо корректировать офсет для response.

---

## Правдоподобные / ограниченные (не severe)

### 7. BootReceiver.onReceive вызывает start() голым, без try/catch
**`BootReceiver.kt:24-26`** · PLAUSIBLE

`start()` теперь **перебрасывает** исключение после rollback (строки 51-54), и все три вызова в `FccViewModel` обёрнуты в try/catch, а BootReceiver — нет. На Android 12+ приём `BOOT_COMPLETED` для типа `connectedDevice` обычно разрешён (не ждём `ForegroundServiceStartNotAllowedException` в штатном сценарии), но в состоянии «Restricted» (ограничение батареи) FGS-старт запрещён → исключение вылетает из onReceive и роняет процесс при загрузке. Любой другой сбой `startForegroundService` на старте тоже уронит.

**Фикс:** обернуть вызов в try/catch — дешёвая защита.

### 8. LAN /api: `fcc_enabled` теперь всегда `null`
**`FccViewModel.kt:1420`** · риск обратной совместимости

Раньше поле несло boolean `isFccEnabled`, теперь захардкожено `null`, а булево переехало в новый ключ `fcc_sequence_written`. Это **задокументировано** (`LAN_CONTROL_API.md:66-68`, осознанный выбор — прокси не даёт физический readback региона), поэтому не нарушение контракта, а риск совместимости: существующие скрипты, читающие `fcc_enabled` как bool, теперь всегда получают null (или падают на строгом парсере).

**Рекомендация:** упомянуть в CHANGELOG / release notes.

### Нюансы (severe-версия отклонена верификацией)

- **Раннее отпускание HardwareLock** (`FccViewModel.kt:1608/1633`, `DumlTransport.kt:363-365`): порчи/перепутанных ответов **нет** — записи сериализованы, строгий матчер `validateResponse` (route/seq/cmd) отбрасывает чужие кадры в `last_unmatched_hex`. Остаётся ограниченный семантический эффект: FCC-apply от keepalive может записать в дрон во время окна чтения LAN-диагностики (до 10с). Осознанный дизайн-выбор (`FccViewModel.kt:250`), severity низкая.
- **Залипший `isFccEnabled` после disconnect** (`FccViewModel.kt:220-227`): флаг остаётся `true` от записи до следующего connect/CE-restore (сбрасывается в `beginHardwareSession`/`clearWriteEvidence`, не на disconnect). Но JSON одновременно отдаёт `connected:false` и метку `written_current_process`, и это задокументировано. Ограниченная и намеренная семантика.

### Отклонено (REFUTED)

- **Краш `stop()` в фоне** (`FccKeepaliveService.kt:75`): `startService` без try/catch реален, но все LAN-пути обёрнуты в `try/catch` в `NetworkLogServer.kt:270-274` → 500, не краш; UI-вызовы только на foreground.

---

## Архитектурный долг (не баги, повторяющаяся тема)

Один и тот же ресурс — единственный DUML-порт дрона — теперь стерегут **четыре** ad-hoc механизма: `Port40007Lock`, `HardwareLock`, `ledOperationBusy`, `lanDiagnosticBusy`. Плюс дублирование:

- `HomePointMonitor` в третий раз реализует разбор/валидацию DUML-фреймов (CRC-8/16, длина), которые уже есть в `DumlTransport.readDumlStream` и `DumlBuilder.validateResponse`.
- `sendBootstrapProfile` дублирует цикл пейсинга/фолбэка портов из `DumlTransport.sendFrames` (лучше добавить параметр `shouldContinue: () -> Boolean`).
- `Port40007Lock.acquireForLed`/`acquireForExternalBlocking` — два почти одинаковых spin-loop'а (`delay(25)`/`Thread.sleep(25)`), а `releaseFromLed(lease)` — просто алиас `lease.close()`.
- `fcc_keepalive.json` теперь мёртвый ассет (правился сегодня, но не загружается).
- `handleLanDuml` / `handleLanDumlCapture` / `handleLanWireExchange` трижды копируют лестницу гейтов с ручным откатом — просится `withLanDiagnostic(port, needsWrite) { ... }`.

**Глубокое решение:** единый `DumlSession` на порт, сериализующий запросы через одну очередь, что убрало бы все четыре лока и три копии парсера. Большой рефакторинг, не для этого набора правок. Но `spin-loop` на 40 пробуждений/сек в бессрочном foreground-сервисе стоит заменить на `Semaphore`/`Mutex` раньше остального — лишний расход батареи контроллера.

---

## Итог

Приоритет фиксов до релиза: **№1–3** (потеря запроса Auto-FCC, тихо не применённый FCC при разовом сбое, краш сервиса без `startForeground`) — прямые следствия перехода на one-shot. №4–6 портят диагностику/UX и корректность определения Home Point. №7–8 — дешёвая защита и заметка в CHANGELOG.
