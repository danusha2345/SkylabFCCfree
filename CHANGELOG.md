# История изменений

## 1.5.22 — 2026-07-20

- Пользовательский flow упрощён: открыть FreeFCC → нажать `Connect`. После
  успешного Connect приложение само ждёт достоверный текущий `Home Point=true`,
  один раз отправляет полный FCC profile и останавливает listener.
- Отдельный Auto-FCC toggle, boot autostart и автоматический запуск DJI Fly
  удалены из пользовательского flow. Старый preference очищается при запуске.
- До явного `Connect` fresh launch не открывает DUML socket. Ошибка listener
  возвращает видимую кнопку `Retry Connect`; сервис работает как non-sticky и
  не восстанавливает незавершённый запрос после перезапуска процесса.
- Предварительный `Home Point=false` больше не требуется. Он только разрешает
  единственный recovery, если уже установленный stream оборвался.
- После отрицательного RC2 A/B-теста `1.5.21` Home Point refresh сохраняет тот
  же TCP socket и cadence 1 Hz, но каждый primer получает следующий DUML
  sequence и пересчитанный CRC. Повтор полностью одинакового frame не продлил
  broker window: соединение снова закрылось примерно через 2 секунды.
- EOF по-прежнему terminal, reconnect не добавлен. Тест проверяет тот же socket,
  последовательный sequence и валидные CRC каждого refresh.

## 1.5.21 — 2026-07-20

- Версия в UI, LAN status, логах и update comparison теперь берётся напрямую
  из `BuildConfig.VERSION_NAME`. Отдельная hardcoded строка больше не может
  отстать от реальной APK metadata.
- Исправлен обнаруженный в `1.5.20` симптом: после успешной установки новый APK
  показывал `1.5.19` и снова предлагал обновление, хотя transport fix уже был
  установлен.

## 1.5.20 — 2026-07-20

- Home Point monitor больше не ждёт неподтверждённый matching response на
  active `03:44`: он принимает только пассивные CRC-valid push-фреймы
  `0x0e → 0x02`, `cmdType=0x00`.
- Один wrapped `03:44` теперь повторяется раз в секунду в том же TCP-сокете
  `40007`, чтобы проверить и поддержать broker telemetry window без опасного
  цикла новых connections. DUML sequence и весь wire-frame переиспользуются.
- EOF и write failure остаются terminal fail-closed: скрытого reconnect loop
  нет. После `Home Point=true`, Auto-FCC OFF или service yield сокет закрывается
  до FCC write.
- JVM-тесты проверяют refresh на том же соединении как при непрерывной
  телеметрии, так и после read timeout.

## 1.5.19 — 2026-07-20

- Auto-FCC больше не открывает Home Point listener до UI/serial probe и запуска
  DJI Fly. При успешном foreground launch monitor ждёт фактический
  `MainActivity.onStop()`; при LAN-команде из уже фонового FreeFCC стартует без
  ожидания повторного lifecycle callback.
- Persistent marker считается живым monitor только вместе с process-local
  `STARTING/RUNNING`; stale marker проходит новый flight-app handoff.
- Проверка `auto_fcc` и service start атомарны с `stop()`, поэтому поздний
  background callback не может воскресить monitor после выключения Auto-FCC.
- Автоматические serial/LED probes исключены из Auto-FCC startup. Startup LED
  gate атомарно блокирует новые и отложенные readback после включения Auto-FCC;
  ручные LED-команды не изменены.
- Terminal Home Point error теперь содержит номер соединения, состояние
  `armed` и факт расходования recovery budget для следующего live-теста.

## 1.5.18 — 2026-07-19

- После подтверждённого `Home Point=false` Auto-FCC может один раз восстановить
  разорванный `40007` stream, сохраняя тот же session gate. Это закрывает live
  случай, когда broker разорвал stream около перехода Home Point и `1.5.17` не
  отправил FCC.
- Recovery budget расходуется до переподключения: второй disconnect либо
  `CONNECT_FAILED` после него завершается fail-closed без третьего socket open.
- Initial `recorded=true` по-прежнему не принимается без ранее увиденного
  `false`; unarmed disconnect не переподключается.
- EOF во время контролируемой уступки порта LED/serial теперь считается
  cooperative stop и не расходует reconnect budget.
- Выключение Auto-FCC сразу очищает terminal-сообщение в UI. Persistent toggle
  остаётся durable autostart-настройкой; повторный вход в FreeFCC является
  явной новой попыткой после terminal failure.
- Устранена гонка старого loopback-теста частичной отправки профиля.

## 1.5.17 — 2026-07-19

- `keepalive_running` больше не запускает Home Point monitor, если
  пользовательский toggle `auto_fcc` выключен. Устаревший marker очищается при
  входе в приложение и при sticky restart сервиса.
- Home Point принимается только как свежий переход `not recorded → recorded`
  текущего запроса. Начальный snapshot `recorded=true` больше не запускает FCC.
- Неожиданный разрыв установленного `40007` stream завершает monitor
  fail-closed. Бесконечного reconnect-цикла больше нет; только initial connect
  имеет одну медленную повторную попытку.
- Listener запускается сразу, чтобы не потерять быстрый Home Point; один session
  gate сохраняется при контролируемой уступке порта LED/serial probe.
- UI больше не называет TCP-write подтверждённым FCC и не утверждает `DEFAULT`
  без readback: отображаются `FCC REQUEST SENT` либо `RF MODE UNKNOWN`.
- Terminal error Home Point monitor больше не затирается поздним
  startup-сообщением.

## 1.5.16 — 2026-07-19

- Auto-FCC ждёт подтверждённый Home Point в одном долгоживущем `40007`
  listener, затем один раз отправляет полный FCC profile и завершает service.
- Старый Auto-FCC worker больше не может остановить, перезапустить или затереть
  состояние нового запроса; worker запускается только после доставленного
  Android `ACTION_START`.
- Автоматический повтор разрешён только если preflight connection не состоялся
  до начала profile frame loop. Возможная частичная запись не повторяется
  скрытно.
- Home Point parser принимает только подтверждённый live-потоком
  `cmdType=0x00`.
- Startup LED read повторяется один раз только при конфликте за port до начала
  wire exchange. После LED ON/OFF выполняется отдельный readback.
- LAN `wire_exchange` теперь отличает `EOF`, deadline, byte limit и mid-stream
  I/O error; при read error сохраняется полученный partial prefix.
- Ошибка запуска foreground service из `BootReceiver` больше не завершает boot
  receiver и сохраняет Auto-FCC request для следующего foreground retry.

Физический RF mode по-прежнему не читается через доступный localhost proxy:
успешная запись profile не равна подтверждённому FCC. Проверяйте Transmission
graph в DJI Fly.
