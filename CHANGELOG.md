# История изменений

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
