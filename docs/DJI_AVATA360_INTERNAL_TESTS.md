# DJI Avata 360: internal diagnostics и factory tests

Дата анализа и live probe: 2026-09-03.

Документ разделяет три независимые поверхности:

1. общий `System Self Diagnostic` из DJI SDK (`cmd_set=0x59`);
2. factory/system test protocol (`cmd_set=0x10`);
3. прямой motor factory-test path через FC parameters.

Наличие API в DJI Fly не означает доступность на серийной Avata. Поэтому ниже
отдельно отмечены static implementation, product config и фактические ответы
живого борта.

## Corpus

| Artifact | Evidence |
|---|---|
| DJI Fly RC2 | `1.21.10`, package `dji.go.v5` |
| `libsdk_jni.so` | SHA-256 `a5d2923e76ff5d231db6df207af8a9c56d4ec7747dede342c51b2958dd6c333d` |
| WA530 `dji_autoflight` | SHA-256 `a3a7d6def0fd4f53b2f63a1f1f8d797330a4bd1732982c8f3166d2f9caeec0cf` |
| Aircraft product | live identity `DJI Avata 360` |
| Aircraft test config | `/etc/perception/json/fstest.json` из WA530 system filesystem |

`dji_autoflight` — ARM64 PIE, 8.5 MiB, stripped, но содержит
`.gnu_debugdata`. Mini debug ELF восстановил имена internal functions без
модификации исходного binary.

## `59:02` System Self Diagnostic Capability

DJI Fly содержит полностью реализованные native request types:

| DUML | Назначение |
|---|---|
| `59:01` | diagnostic mode switch |
| `59:02` | capability/parts query |
| `59:03` | keepalive |
| `59:04` | execute test/action |
| `59:05` | terminate |
| `59:06` | progress push, command ID derived from sequence |
| `59:07` | result/file request |

`59:02` отправляется в `DM368`, raw destination `0x08`. Request payload:

```text
01 <diagnostic_target> <capability>
```

Capability:

- `01 SELF_TEST`;
- `02 ACTION`.

Общий SDK перечисляет parts `ESC`, `LINK`, `AVIONICS`, а также agricultural
`NOZZLE`, `PUMP`, `DELIVER`, `THROW`, `MATRIAL`. Возможные ESC actions:
`MOTOR_BEEP`, `MOTOR_VERY_SLOW_ROTATE`, `MOTOR_SLOW_ROTATE`,
`MOTOR_STOP_ROTATE`.

### Live result

```text
TX dst=08 cmd=59:02 payload=01 00 01
RX src=08 cmd=59:02 payload=E0

TX dst=08 cmd=59:02 payload=01 00 02
RX src=08 cmd=59:02 payload=E0
```

Selector перебран в ограниченном диапазоне `0..8`; полученные ответы также
были `E0`. В DJI legacy `Ccode`, `0xE0 = INVALID_CMD`. FC `dst=03` на этот
command set не ответил.

**Verdict:** активный DM368 Avata не реализует `59:02`. Перечень
`ESC/LINK/AVIONICS` относится к общей SDK-поверхности и не является списком
возможностей этого борта.

## FSTest protocol `cmd_set=0x10`

Native SDK реализует:

| DUML | Request | Эффект |
|---|---|---|
| `10:10` | `hostId`, enable byte | enable/disable framework |
| `10:11` | `hostId`, case name | запуск case |
| `10:12` | `hostId`, case name | состояние/counters/error |
| `10:13` | `hostId`, reserved `00` | число cases |
| `10:14` | `hostId`, `uint32 LE index` | имя/info case |

`hostId` — составной routing ID:

```text
device_index = hostId & 0x07
device_type  = hostId >> 3
```

Aircraft config содержит точное значение:

```json
"host": [18, 4]
```

Следовательно:

```text
SDK hostId = (18 << 3) | 4 = 0x94
DUSS raw destination = 18 | (4 << 5) = 0x92
```

Это подтверждается пассивным live traffic: `src=0x92` отправляет navigation
push `23:B2`. В `dji_autoflight` internal message descriptors также содержат:

```text
0x12041010 -> host 18:4, cmd 10:10 FSTest enable
0x12041110 -> host 18:4, cmd 10:11 run case
0x12041210 -> host 18:4, cmd 10:12 get case status
```

### Live result

Read-only discovery:

- camera `01`, `10:13` -> `E0 INVALID_CMD`;
- DM368 `08`, `10:13` -> `E0 INVALID_CMD`;
- camera `01`, `10:14 index=0` -> `E0 INVALID_CMD`;
- FC, center, OSD, battery, GPS, FPGA и другие известные endpoints не ответили;
- direct navigation host `92`, `10:13` не ответил.

Дополнительно выполнена контролируемая последовательность:

```text
dst=92 10:10 payload=01  ENABLE
dst=92 10:13 payload=00  GET COUNT
dst=92 10:10 payload=00  DISABLE
```

ACK ни на один пакет не получен; disable всё равно был передан. Ни один case
через `10:11` не запускался. Наиболее вероятная граница — internal FSTest host
не экспортирован через shared RC2 injection route или production service
закрыт policy/config gate.

После эксперимента `10:10 payload=00 DISABLE` дополнительно повторён три раза
с отдельными sequence numbers. ACK по-прежнему отсутствовал. Это не доказывает
доставку до internal service, но исключает намеренное оставление framework в
enabled state со стороны тестового клиента.

Для отличия закрытой enumeration от закрытого framework выполнен ещё один
read-only запрос состояния существующего case:

```text
dst=92 cmd=10:12
payload=0F "CaseAutoTakeOff"
```

Префикс `0F` — длина 15-byte ASCII имени, формат подтверждён disassembly
`nav_v1_get_fstest_case_status()`. Ответ также не получен. Это подтверждает,
что через текущий RC2 route недоступен весь FSTest host, а не только команды
`10:13/14`.

Проверены оба RC2 транспорта:

- `40008` injection + `40007` response mirror;
- `40009` direct broker с чтением ответа на том же socket.

На `40009` виден живой поток, адресованный slot `0x82` (APP type 2, index 4),
но Termux write не проходит даже для известного `03:34 GetPlaneName` с source
`0x82`. Следовательно, этот broker для непривилегированного Termux фактически
read-only. На `40008` известные FC-команды проходят, но запросы к internal
host `0x92` не возвращают ACK.

Из `router.json` восстановлены роли:

| Role | Host | Raw DUSS address |
|---|---|---:|
| autoflight client | `mvision:2` | `0x52` |
| factory autotest | `mvision:3` | `0x72` |
| FSTest server | `mvision:4` | `0x92` |

`10:13` дополнительно отправлен с impersonated source `0x52` и `0x72`; ответа
в RC2 mirror нет. Возможный internal reply был бы адресован aircraft-side
client, поэтому этот отрицательный результат слабее прямого `E0`, но не даёт
практического способа перечисления через RC2.

## Product test plan из `fstest.json`

Конфигурация показывает, что этот framework в первую очередь предназначен для
лабораторных сред:

- `HIL` — hardware-in-the-loop;
- `SIL_LITE` — software-in-the-loop;
- `AF` — отдельная autoflight-группа.

Основные зарегистрированные группы:

| Среда | Cases |
|---|---|
| HIL flight | `CaseAutoTakeOff`, `CaseVerticalFly`, `CaseHorizontalFly`, `CaseGoHome` |
| HIL smoke | `CaseParamTest`, storage/log checks, normal/tripod velocity, yaw, scene manager, shared-memory test |
| AF | `CaseAutoTakeOff`, `CaseVehicleLanding` |
| HIL diagnostics | flight-control log, simulator start, motor external error, MCU state fault, HMS |
| SIL_LITE | offline map, camera, gimbal и abstract data layer tests |
| HIL fault injection | `FitAbstDataFusionOut`, `FitAbstDataFlyCore`, `FitAbstDataFlyExt` |
| HIL monitoring | thread count/name, CHM state, DDR/DSP/reset checks |

Всего в `fstest.json` — 70 вхождений и 61 уникальное имя case. Конфигурация
явно содержит опасные для наземного запуска cases: auto takeoff, vertical и
horizontal flight, yaw, Go Home, vehicle landing, AP/perception reset,
simulator start и fault injection.

То есть даже получение имени case не означает, что его допустимо запускать на
земле: часть cases автоматически взлетает, летит, возвращается, садится,
перезапускает подсистемы или вносит artificial faults.

## Скомпилированные autoflight cases

`.gnu_debugdata` раскрыла 98 функций `case_*_cfg_reg`. Они включают:

### Моторы, ESC и конструкция

- `case_fac_esc`, `case_fac_new_esc`, `case_fac_ag_esc`;
- `case_fac_motor_beep`;
- `case_blades`;
- `case_fac_body_vibration`, `case_fac_vib`;
- `case_fac_fastening_detection`;
- `case_fac_takeoff_check`.

### Навигация и полёт

- `case_fac_gnss`, `case_fac_gps_drift_check`;
- `case_fac_hover_drift`, `case_fac_hover_height`, `case_fac_hover_peak_det`;
- `case_fac_home_precise`, `case_af_precise_land`, `case_af_precise_sample`;
- `case_return_home`, `case_fac_return_home`;
- `case_flight_avoid`, `case_brake`, `case_out_control`;
- `case_nav_apas`, tracking, panorama, QuickShot, MasterShot, waypoint and WPMZ;
- `case_reboot`, `case_fac_shutdown`.

### Sensors и control state

- barometer, compass, IMU check/switch, VIO, ToF, laser;
- turn-yaw drift, IP calibration, temperature and DDR monitoring;
- read/write parameter cases.

### Camera, gimbal и perception

- photo/video/record/zoom/mode-switch cases;
- gimbal calibration, turn, vibration, angle and joystick;
- perception 3 m distance, camera adjustment/reset/test, IR point temperature.

Это **compiled surface**, не доказательство, что все 98 cases включены в
production test plan WA530. `fstest.json` является более сильным evidence для
реально сконфигурированных групп.

## Отдельный QR/factory autotest framework

WA530 system image содержит ещё один framework, независимый от `cmd_set=0x10`:

- executable `/system/bin/dji_autotest`;
- config `/system/etc/dji_autotest/json/atconfig.json`;
- marker `/data/dji/autotest_on` или `/data/dev/autotest_on`;
- скрипт `dji_autotest_on.sh` создаёт/удаляет эти marker files;
- route `autotest_service/autotest = mvision:3` (`0x72`).

`atconfig.json` объявляет 29 QR triggers. Среди них:

- `wa530-barotest`, `wa530-imucali`, `wa530-imubias`, `wa530-compass`;
- perception/optical `combine`, `parallel`, `fpnc`, `fishmtf`, `binocali`,
  `dist3m`, exposure ratio и fill light;
- набор 3D-ToF calibration/test variants;
- `wa530-gimbalcali`, `wa530-gimbalyaw`;
- `wa530-reboot`;
- `autotest-indoor-flytest`.

Последний trigger загружает `at_autoflight.so`. `test_action.json` показывает,
что flight tests действительно исполняют `Takeoff`, `Hover`, horizontal route,
yaw, calibration and video actions. Это не UI QR-функция DJI Fly, а заводской
autotest service, ожидающий специальный marker/config и подготовленный стенд.

Подписки autotest включают FC `03:5A` motor, `03:5B` state, `03:6A` sensor,
`03:AB` barometer; gimbal calibration/vibration, vision debug/calibration,
RC sticks/buttons, ESC LED, ToF и perception data. Наличие этих subscriptions
не делает их самостоятельными безопасными test commands.

## Direct `StartMotorControl`

DJI Fly содержит old и current implementations. Current path:

1. проверяет `motorIndex=1..4`;
2. выбирает `set_motor_auto_start_N_0`;
3. вызывает `SetMotorPWM`;
4. вызывает `SetMotorRunTime`;
5. включает factory-test motor path.

В Avata parameter table присутствуют:

| Index | Name | Range | Live/saved value |
|---:|---|---|---:|
| 1183–1186 | `set_motor_auto_start_1..4` | `U32` | 0 |
| 1473 | `user_test_std_output` | `0..10000` | 500 |
| 1474 | `user_test_timer` | `0..65535` | 20 |
| 1475 | `user_test_esc_factory_test` | `0..1` | 0 |

Old path масштабирует `runPwm × 100`, поэтому внешний `runPwm` вероятно
измеряется в процентах `0..100`. Единицы `runTime` не доказаны. Рядом найден
`03:E9 uav_fc_set_cmd_handler_req`, но current action состоит из нескольких
config writes и command step.

После live probes `set_motor_auto_start_1` повторно прочитан через `03:E2` и
равен `0`. Сохранённый полный dump показывает нули для всех четырёх auto-start
fields и `user_test_esc_factory_test=0`. Motor-control writes не выполнялись.

### Расширенный live snapshot test parameters

Повторный `03:E0` подтвердил Avata table `count=1561`, CRC `0x430BD5B7`.
Через `03:E1/E2` получены:

| Index | Name | Current | Interpretation |
|---:|---|---:|---|
| 11 | `sweep_test_method` | 0 | sweep method idle/default |
| 25 | `sweep_vib_flag` | 0 | vibration sweep inactive |
| 555 | `esc_recv_test_fdi_open` | 1 | штатный ESC receive FDI monitor enabled |
| 1061 | `test_emergency_enable` | 0 | emergency test gate disabled |
| 1183–1186 | `set_motor_auto_start_1..4` | 0 | все motor auto-start gates disabled |
| 1473 | `user_test_std_output` | 500 | default test output setting |
| 1474 | `user_test_timer` | 20 | default test timer setting |
| 1475 | `user_test_esc_factory_test` | 0 | ESC factory test inactive |

Для `sweep_test_flag` index 10 metadata (`U8`, range `0..18`, default `0`)
получена, но current-value response дважды потерялся в нестабильном RC2
capture; сохранённый полный live dump содержит value `0`. Значение
`esc_recv_test_fdi_open=1` не означает активную fault injection: это enable
обычной FDI-проверки входящего ESC channel.

## Итог

| Claim | Level | Evidence | Verdict |
|---|---|---|---|
| `59:02` существует в DJI SDK | `OBSERVED` | APK + native request type | да |
| Avata DM368 поддерживает `59:02` | `NEGATIVE` | live `E0 INVALID_CMD` | нет на текущей firmware |
| WA530 содержит FSTest implementation | `OBSERVED` | `dji_autoflight`, `.gnu_debugdata`, `fstest.json` | да |
| FSTest доступен через RC2 shared port | `NEGATIVE` | no ACK from exact host `0x92` | не подтверждено/вероятно закрыто |
| Cases безопасны на земле | `NEGATIVE` | auto-takeoff/flight/landing/fault/reboot cases | нет |
| Avata содержит motor-test primitives | `OBSERVED` | model table + native handlers | да |
| Motor-test сейчас активен | `NEGATIVE` | dump + live E2 readback | нет |

### Финальный live snapshot 2026-09-03

- aircraft name через `03:34`: `DJI Avata 360`;
- failsafe через `03:3C`: `02 = GoHome`;
- первый voltage warning через `03:30`: `15%`, action byte `0`;
- `03:46` включение GPS-SNR push подтверждено ответом `01`, но за 4 секунды
  `03:45` не появился; push затем выключен;
- пассивный bus по-прежнему показывал живой `mvision:4` source `0x92`, включая
  `23:B2`, `0A:BC`, `0A:5A` и `22:21`;
- три GPS-FDI параметра временно проверены A/B; ни один вариант не снял
  `GPS_NOT_READY`, все исходные значения подтверждённо восстановлены;
- `StartMotorControl`, motor rotation, blade calibration, takeoff, landing,
  reboot, fault injection и factory test cases не запускались;
- каждый временный клиент `40007/40008` был закрыт после запроса.

Следующий технически сильный шаг — получить доступ к DUSS IPC непосредственно
внутри aircraft Linux либо вызвать штатный KSDK action из процесса DJI Fly.
Повторный raw sweep с RC2 не даст новой информации: точный host уже найден, но
shared injection route не возвращает его ACK.
