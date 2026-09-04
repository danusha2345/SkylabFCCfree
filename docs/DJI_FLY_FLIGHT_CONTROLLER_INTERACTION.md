# DJI Fly 1.21.10: взаимодействие с полётным контроллером

Дата анализа: 2026-09-03.

Этот документ отвечает на три разных вопроса:

1. Что штатный интерфейс DJI Fly действительно позволяет менять на борту.
2. Какие дополнительные операции присутствуют в SDK и коде приложения, но
   могут быть скрыты, model-specific или недоступны на серийном аппарате.
3. Может ли DJI Fly менять сами алгоритмы полётного контроллера, а не только
   их настройки.

Короткий ответ: **DJI Fly имеет широкий двусторонний канал управления бортом и
меняет много persistent и runtime-настроек, но обычные настройки приложения не
заменяют исполняемый алгоритм FC**. Приложение задаёт лимиты, режимы, параметры
реакции, safety policy, цели интеллектуальных миссий и запускает уже имеющиеся
на борту actions. Замена кода алгоритма возможна только как отдельное обновление
firmware, а не как key-value/DUML setting.

## Корпус и границы доказательства

Основной артефакт:

| Поле | Значение |
|---|---|
| APK | `~/storage/dji-fly-mod-analysis/RC2-Fly-1.21.10-original.apk` |
| package | `dji.go.v5` |
| version | `1.21.10`, `versionCode=3115981` |
| ABI | `armeabi-v7a`, вариант для RC2 |
| SHA-256 APK | `f6c11267d465c303e2aecb737fe4f6b41b54da083368cac497404f4c9a464f98` |
| `libsdk_jni.so` SHA-256 | `a5d2923e76ff5d231db6df207af8a9c56d4ec7747dede342c51b2958dd6c333d` |
| `libsdk_key_value.so` SHA-256 | `dee424e3458a92f6f9e8cbf3642d44a458f3e34507ad9fc07b2016d8df13ffca` |
| `res/raw/flyc_param_infos` SHA-256 | `dd4530e655fdb9ffd242209bc8674cec66e4376a8e7ebca9930fdc40c4264b0b` |

APK полностью декомпилирован через JADX 1.5.6: 52 680 классов обработано,
461 класс декомпилирован с ошибками. Поэтому положительные находки по конкретным
классам надёжны, а отсутствие произвольной функции во всём приложении нельзя
считать абсолютным доказательством.

Для сравнения проверен реестр DJI Fly 1.21.8. Наборы и capability flags в
`UAVFlightControllerKey`, `UAVFlightAssistantKey` и
`UAVRemoteControllerKey` одинаковы по именам и правам между 1.21.8 и 1.21.10.
Дальнейшие выводы основаны на 1.21.10.

Уровни evidence:

- `OBSERVED` — непосредственно найдено в APK, ELF, decompiled source или
  живом capture.
- `DERIVED` — однозначно следует из нескольких observed-фактов.
- `HYPOTHESIS` — нужен аппаратный тест или wire capture.
- `NEGATIVE` — искомое не найдено в обозначенном объёме; не означает
  невозможность во всех версиях и моделях.

## Архитектура пути до борта

Для современных функций DJI Fly использует следующий путь:

```text
экран/кнопка/slider DJI Fly
  -> FlyModel (типизированная бизнес-модель)
  -> UAVKeyManager / RxCSDK / KSDK key-value
  -> libsdk_jni.so + libsdk_key_value.so
  -> DUML/DUSS framing и product-specific resolver
  -> link/router пульта
  -> flight controller, vision/navigation, radio или другой модуль борта
  -> ACK / response / push telemetry
  -> key-value cache -> FlyModel -> UI
```

`OBSERVED`: `SettingAltitudeLimitViewModel` читает диапазон и текущее значение
через `FlyModel.getFlight().getFlyLimit()` и записывает новое значение через
`getLimitHeight().j(...)`. Аналогично работают distance limit, RTH height,
lost-link action, GNSS source, obstacle avoidance и APAS.

`OBSERVED`: modern resolver находится в `libsdk_key_value.so`. Он содержит
строковые имена keys, product index, сериализаторы value messages и строит
wire-frame. Один и тот же Java key поэтому может разрешаться в разные wire
команды на разных моделях или быть помечен unsupported.

`DERIVED`: имя key или наличие UI-класса не является универсальной гарантией.
Реальный доступ зависит минимум от product type, firmware capabilities,
диапазона значений, текущего состояния аппарата и policy gates приложения.

### Это не один «канал в FC»

Не все функции адресованы непосредственно flight MCU:

- `FLIGHTCONTROLLER` — лимиты, home point, RTH/failsafe, GNSS state, IMU,
  compass, power/motor state, RID и часть control parameters;
- `FLIGHTASSISTANT` — obstacle avoidance, APAS, landing protection,
  ActiveTrack, QuickShots, MasterShots, POI, timelapse и navigation missions;
- `REMOTECONTROLLER` — стики, button mapping, soft flight-mode switch,
  calibration и authority;
- camera/gimbal/airlink имеют собственные компоненты и command sets.

Например, intelligent-flight actions в нативной таблице используют не только
`cmd_set=0x03` (FC), но также `0x0A` (vision) и `0x23` (navigation). Поэтому
фраза «DJI Fly послал всё в полётный контроллер» технически неточна: приложение
управляет распределённой системой борта.

## Что штатный DJI Fly реально меняет через UI

В `com/uav/component/fpv/widget/setting/ui` найдено 182 класса
`*ViewModel`; часть относится к camera/gimbal/liveview, а перечисленные ниже
группы непосредственно влияют на полёт или его safety policy.

### Лимиты и геозоны

| Возможность | Реальный путь | Эффект |
|---|---|---|
| Maximum altitude | `SettingAltitudeLimitViewModel` -> `limitHeight` | меняет лимит высоты в доступном диапазоне |
| Maximum distance | `SettingRadiusLimitViewModel` -> distance value + enable | меняет радиус и включает/выключает distance limit |
| RTH height | `SettingReturnHeightViewModel` -> `rthHeight` | меняет высоту возврата |
| C0/CE UI cap | `EuropeCELimitUtil` участвует в вычислении диапазона | ограничивает допустимый slider/range на стороне приложения |
| altitude unlock | отдельный `SettingAltitudeLimitUnlockViewModel` | проходит capability/account/area policy, а не произвольную запись |
| flysafe/NFZ | flight-restrict service, DB transfer и authorization actions | обновляет базы и применяет разрешённые unlock records |

Flight limit состоит из нескольких слоёв. App-side C0/CE range, параметр FC,
режим новичка, отсутствие positioning и aircraft firmware checks не
эквивалентны. Запись одного FC-параметра не отключает остальные слои.

### Return-to-Home и потеря связи

DJI Fly читает и/или меняет:

- `GoHomeHeight` и доступный диапазон;
- `FailsafeAction` — политика при потере управления;
- `GoHomePathMode` и доступные варианты маршрута;
- `SmartBatteryRTHEnabled`;
- `GoHomeSpeed` на поддерживаемых платформах;
- dynamic home point;
- home point по текущему положению борта или пульта;
- obstacle avoidance во время RTH;
- подтверждение low-battery auto-RTH и landing countdown.

Отдельные actions запускают и останавливают RTH. Это команды state machine,
а не изменение RTH-алгоритма.

### Управляемость и динамика

В штатном key registry 1.21.10 writable для consumer FC присутствуют:

- EXP: pitch/roll, throttle и yaw для Normal/Sport/Tripod/Gentle;
- yaw rate и yaw buffer/smoothness по режимам;
- brake sensitivity по режимам;
- горизонтальные, ascending и descending speed limits;
- `RCScaleInNormal/Sport/Tripod`;
- adaptive speed control;
- coordinated turn;
- channel mappings и custom function mode;
- multiple flight mode enable;
- `TiltAttitude` и отдельные product-specific control settings.

Это **настройки встроенного control law**, поэтому они действительно меняют
поведение и коэффициенты внешнего пользовательского контура. Но они не заменяют
estimator, PID/ADRC implementation или motor mixer кодом из приложения.

### GNSS и позиционирование

Штатный 1.21.10 умеет:

- выбирать `GnssSourceMode` / `GNSSUserSetMode` из поддержанного range;
- на некоторых конфигурациях переключать GNSS vs BeiDou-only;
- менять `NavigationSatelliteSystemSource`;
- включать/выключать отправку GPS SNR telemetry;
- передавать mobile/RC position для dynamic home point;
- в SDK имеет `FCGPSEnabled` и `VisionAssistedPositioningEnabled`.

`OBSERVED`: видимость GNSS selector дополнительно привязана к region `CN`,
product/config capability и поддержанному диапазону. Наличие key не означает,
что соответствующий пункт показывается на любом consumer drone.

`NEGATIVE`: в key registry и в 40 нативных FC request templates не найдена
команда power-off/standby самого GNSS receiver. Можно менять использование
GNSS в fusion или выбранное созвездие, но это не доказывает снятие питания с
приёмника.

### Obstacle avoidance, vision и APAS

Штатные экраны и FlyModel меняют:

- общий user obstacle-avoidance switch;
- horizontal/upward/downward avoidance;
- avoidance distances;
- RTH obstacle avoidance;
- bypass mode и safety level;
- APAS enable;
- landing protection и precision landing;
- downward vision positioning;
- auxiliary light mode;
- product-specific radar enable/distance.

UI каждый раз проверяет product type, vision version, capability range и
наличие настройки. В одном из путей `SettingObstacleSwitchViewModel` вызывает
set для `UserAvoidanceEnabled`, хотя Java metadata key помечает его без `S`.
Это показывает, что автоматический подсчёт capability flags полезен как карта,
но не заменяет проверку call site и результата на борту.

### Калибровки, диагностика и обслуживание

Приложение может запускать/останавливать или контролировать:

- IMU calibration;
- compass calibration;
- center-of-gravity calibration;
- blade calibration на поддержанных изделиях;
- декларативные propeller-calibration keys, поддержка которых должна
  подтверждаться на конкретной модели;
- EMF/LLG/pump/sower calibration для enterprise/agricultural products;
- ESC/motor beep;
- log export и clear-log procedures;
- self-diagnostic requests;
- reset settings/factory settings с проверкой motors-off и connection state.

Калибровка запускает уже реализованную в firmware процедуру и может изменить
её calibration data/state. Это не загрузка нового sensor-fusion algorithm.

#### Gravity Center Calibration

Это не калибровка акселерометра и не определение направления гравитации.
Процедура предназначена для калибровки **центра масс аппарата** по поведению в
стабильном висении. Официальное описание DJI требует, чтобы аппарат уже летел,
висел без движения и чтобы не было ветра.

В APK подтверждены следующие пути:

| Операция | SDK key / legacy name | DUML |
|---|---|---|
| запуск | `StartGravityCenterCalibration` / `MASS_CENTER_CALI=54` | `03:2A`, payload `36` |
| остановка | `StopGravityCenterCalibration` / `EXIT_MASS_CENTER_CALI=55` | `03:2A`, payload `37` |
| состояние | `GravityCenterState` / `MASS_CENTER_CALI_STATUS` | push `22:14`, payload 2 bytes |

Первый байт push-сообщения — состояние:

- `0` — `STANDBY`;
- `1` — `CALCULATING`;
- `2` — `FINISHED`;
- `3` — `FAILED`.

Второй байт — причина завершения:

- `0` — success;
- `1` — manual exit;
- `2` — аппарат не удерживал hover;
- `3` — слишком сильный ветер.

В SDK также присутствуют отдельные ошибки `NOT_FLYING`, `SIMULATOR_RUNNING`,
`IS_RUNNING` и `NOT_HOVERING`. Поэтому запускать эту процедуру на земле для
«проверки команды» бессмысленно: штатный ожидаемый ответ — отказ. APK
подтверждает запуск расчёта и его state machine, но не раскрывает конкретные
коэффициенты, которые FC вычисляет или сохраняет.

#### Blade Calibration

В публичном key registry операция называется `StartBladeCalibration`, а в
legacy FC enum — `OAR_PANEL_CALI=47`. Java и ARM-disassembly независимо
подтверждают один и тот же wire request:

```text
cmd_set=03, cmd_id=2A (FunctionControl), payload=2F 02
```

`2F` — код `OAR_PANEL_CALI`; значение второго байта `02` жёстко задано как в
`DataFlycFunctionControl.doPack()`, так и в
`FlightControllerAbstraction::SendBladeCalibrationPack()`.

Найден только action запуска: отдельного status key, stop action и штатного UI
call site в проверенной сборке DJI Fly 1.21.10 нет. Название указывает на
сервисную калибровку лопастей/ротора для отдельных типов изделий, однако APK не
раскрывает её физический алгоритм. Нельзя считать её универсальной процедурой
балансировки пропеллеров consumer-квадрокоптера. Команда потенциально может
запустить моторную процедуру, поэтому без model capability и сервисной
инструкции её на живой Avata 360 не отправлять.

#### Propeller Calibration

В сгенерированном общем реестре присутствуют:

- `StartPropellerCalibration` — action;
- `StopPropellerCalibration` — action;
- `PropellerCalibrationStatus` — boolean push/get key.

Но в 1.21.10 не найдено ни одного UI/FlyModel call site, а в
`libsdk_jni.so` нет обработчиков start/stop/status, аналогичных обработчикам
Gravity Center и Blade. Имена присутствуют только в общем key registry и
`libsdk_key_value.so`. Следовательно, это **SDK surface для возможных
product-specific реализаций**, а не доказанная рабочая FC-команда для Avata
360. Не следует смешивать её с независимыми push-ключами проверки установки
пропеллеров, propeller guards, обледенения и abnormal-state.

#### ESC и propeller diagnostics: живой Avata 360

Наземный capture от 2026-09-03 дал FC push `03:44` длиной 102 bytes. Поле
`MotorEscmState` в payload offsets `41..44` было равно `0x0000AAAA`:

| Каналы | Значение | Расшифровка |
|---|---:|---|
| ESC 1–4 | `0xA` каждый | `MOTOR_OFF`, штатное состояние на земле |
| ESC 5–8 | `0x0` каждый | `NON_SMART`, отсутствующие каналы |

Ошибки `DISCONNECT`, `SIGNAL_ERROR`, `RESISTANCE_ERROR`, `BLOCK`,
`NON_BALANCE`, `ESCM_ERROR` и `PROPELLER_OFF` не наблюдались. Дополнительный
read-only запрос `03:ED`, payload `05 <index>` (`REQUEST_SOME`) вернул для
ESC 1–4 одинаковый ответ `00 00`: `SUCCESS`, `echoing=false`.

`03:ED` относится к ESC beep/echo, а не к полной диагностике регулятора.
Диагностический state берётся из `03:44`. Проверка выполнялась без вращения
моторов и поэтому не исключает неисправность, проявляющуюся только под
нагрузкой.

Живой motor-beep test на Avata 360:

1. отправлен `03:ED`, payload `02 00` (`OPEN_ALL`);
2. FC ответил `00 00` (`SUCCESS`), пользователь физически подтвердил писк;
3. через 5 секунд отправлен `03:ED`, payload `01 00` (`CLOSE_ALL`);
4. ACK stop не попал в capture, но последующий `REQUEST_SOME` для ESC 2 и 3
   вернул `00 00` (`SUCCESS`, `echoing=false`); ESC 1 и 4 не ответили в
   коротком окне чтения.

Таким образом, физический эффект `OPEN_ALL` подтверждён, STOP был передан по
wire и доступный readback после теста показывает выключенный echo. Все TCP
сокеты после операции закрыты.

Дополнительный финальный live snapshot дал: `03:34 = DJI Avata 360`,
`03:3C = 02 (GoHome failsafe)`, `03:30 level 1 = 15%`. Переключатель GPS-SNR
push `03:46` принял enable (`01`), но без готового GNSS за 4 секунды не пришёл
ни один `03:45`; push после probe выключен.

### Полётные actions

В `UAVFlightControllerKey` присутствуют штатные actions:

- start/stop takeoff;
- start/stop/confirm landing;
- start/stop RTH;
- установка home point;
- virtual-stick data;
- request/release joystick-control authority;
- simulator start/stop;
- motor-control и display/test modes;
- reboot, reset, log export и firmware-support operations.

В `UAVFlightAssistantKey` присутствуют actions для QuickShots, MasterShots,
ActiveTrack/SmartEye, POI, navigation, timelapse и product-specific missions.
Приложение передаёт цель, геометрию, скорость, направление или параметры
миссии и затем получает progress/status pushes. Траекторию и стабилизацию
исполняют aircraft-side vision/navigation/FC modules.

## Полная SDK-поверхность 1.21.10

Подсчёт сделан по literal key registrations, а не по строкам UI:

| Component | Всего keys | Get | Set | Listen | Actions |
|---|---:|---:|---:|---:|---:|
| `FLIGHTCONTROLLER` | 794 | 635 | 214 | 581 | 137 |
| `FLIGHTASSISTANT` | 738 | 598 | 162 | 594 | 121 |
| `REMOTECONTROLLER` | 268 | 191 | 49 | 186 | 58 |

Колонки пересекаются: один key может поддерживать get+set+listen; action —
отдельный тип. Это поверхность общего DJI SDK для многих consumer,
enterprise и agricultural products, а не список гарантированно работающих
операций на одном Air 3S/Avata/Mini.

Наиболее важные writable FC families:

| Семейство | Примеры keys |
|---|---|
| Limits | `HeightLimit`, `DistanceLimit`, `DistanceLimitEnabled`, `GoHomeHeight` |
| Response | `*PitchRollExp`, `*ThrottleExp`, `*YawExp`, `*BrakeSensitivity`, `*YawRate`, `RCScale*` |
| Speed | `*MaxHorizontalSpeed`, `*MaxAscendingSpeed`, `*MaxDscendingSpeed`, `AutoFlightSpeed` |
| Safety | `FailsafeAction`, `EmergencyStopMotorEnable`, `FCUrgentStopMotorMode`, voltage thresholds |
| GNSS | `FCGPSEnabled`, `GnssSourceMode`, `GNSSUserSetMode`, `NavigationSatelliteSystemSource` |
| Position/home | `HomeLocation`, `HomeLocationWithType`, `ConfirmLandingHeight` |
| Lights | `LEDsSettings`, `ExtLedCtrlParam`, atmosphere-light keys |
| Identity/regulatory | `AircraftName`, `FCAreaCode`, RID/EID/operator fields |
| Product-specific | radar, RTK, agriculture, spray/sower и terrain settings |

## Legacy FLYC parameter manager: самый широкий, но не штатный UI

APK 1.21.10 содержит `res/raw/flyc_param_infos`: локальную JSON-таблицу из
687 записей. По attribute:

| attribute | Количество | Смысл в legacy enum |
|---:|---:|---|
| 0 | 123 | read-only |
| 1 | 100 | read/write runtime |
| 3 | 2 | EEPROM read/write |
| 11 | 462 | EEPROM read/write + import/export |

Таблица содержит не только пользовательские настройки, но и низкоуровневые
algorithm/model parameters:

- `g_config.control.basic_pitch_0`, `basic_roll_0`, gains и damping;
- horizontal/vertical position/velocity gains;
- attitude ranges и manual angular rates;
- `control_mode[0..2]`;
- IMU/GPS offsets и compass calibration coefficients;
- motor/propeller physical-model coefficients;
- mixer, servo, voltage, failsafe и flight-limit parameters.

В Java legacy-layer есть `DataFlycGetParamInfo`, `DataFlycGetParams`,
`DataFlycSetParams`, а в `libsdk_jni.so` подтверждены:

| DUML | Native request type |
|---|---|
| `03:F7` | `uav_fc_get_get_cfg_item_info_by_hash_req` |
| `03:F8` | `uav_fc_read_hash_param_req` |
| `03:F9` | `uav_fc_set_write_hash_param_req` |
| `03:FA` | `uav_fc_set_reset_cfg_item_by_hash_req` |

Это доказывает, что приложение **содержит общий механизм произвольного чтения
и записи поддержанных FC parameters**. Но есть три существенные границы:

1. Штатный DJI Fly 1.21.10 не предоставляет пользователю произвольный editor
   этой таблицы. Большинство обычных экранов идёт через modern key-value path.
2. Локальная таблица на 687 записей не является паспортом современного борта.
   На живом Air 3S `03:E0` сообщил model-specific таблицу на 1513 entries;
   имена, индексы, допустимые ranges и write policy обязан подтвердить сам FC.
3. Наличие имени/serializer в APK не доказывает, что конкретная firmware
   принимает запись или что она безопасна. Нужны metadata, ACK, readback после
   reconnect/power cycle и физическое наблюдение.

Поэтому наличие PID/gain/model fields означает **техническую возможность
параметрического тюнинга при поддержке борта**, но не доказывает возможность
заменить алгоритм или безопасно менять все коэффициенты на современном drone.

## Native FC command surface

В template symbols `libsdk_jni.so` найдены 40 request types с
`cmd_set=0x03`. Среди них:

- `03:20` передача в FC app/RC location, времени и состояния сети;
- `03:2A` function control;
- `03:31` set home point;
- `03:33/34` set/get aircraft name;
- `03:3B/3C` set/get failsafe action;
- `03:45/46` GPS SNR push и его переключатель;
- `03:80` ground-station on/off;
- `03:93` legacy GPS-information message;
- `03:9C/9D` set/get waypoint auto-flight speed;
- `03:AF` product config;
- `03:B4` UAV code;
- `03:B5/B6` fault injection и push результата;
- NFZ database upgrade/status commands;
- `03:F7..FA` parameter metadata/read/write/reset;
- `03:FE` motor-force-disable flag.

Это не полный трафик DJI Fly: push commands и command sets vision/navigation,
camera, gimbal, RC и airlink существуют отдельно. Таблица лишь показывает,
что modern native layer содержит конкретные FC request serializers, а не
только Java declarations.

### `03:20` — `SendGpsToFlyc`

`DataFlycSendGpsToFlyc` строит 13-byte payload в little-endian:

| Offset | Size | Поле | Кодирование |
|---:|---:|---|---|
| 0 | 1 | flags | bit 0 `gpsValid`, bit 1 `timeValid`, bit 2 `networkOk` |
| 1 | 4 | latitude | signed `int32`, градусы × 1 000 000 |
| 5 | 4 | longitude | signed `int32`, градусы × 1 000 000 |
| 9 | 4 | timestamp | младшие 32 bits Unix time в секундах |

Java setter принимает timestamp в миллисекундах и перед упаковкой делит его
на 1000. Значения по умолчанию: GPS valid, time valid, network not OK.

Это сообщение передаёт FC позицию источника со стороны приложения/пульта и
служебные признаки валидности. Оно **не содержит** количество спутников,
accuracy/DOP, velocity, GNSS raw measurements, ephemeris или satellite IDs.
Поэтому само по себе не способно изобразить полноценный fix бортового GNSS и
не является прямым способом обойти проверку спутников для Home Point. Точное
назначение этих данных внутри firmware конкретной модели из APK не видно;
возможные потребители — время, положение оператора, geospatial logic и legacy
dynamic-home-related функции.

В DJI Fly 1.21.10 вне самого model-класса живой Java call site не найден.
Следовательно, наличие serializer — `OBSERVED`, регулярное использование
штатным Fly на Avata 360 — не подтверждено.

### `03:45/46` — GPS SNR telemetry

Направления здесь разные:

- `03:46 SetPushGpsSnr` — app → FC, payload один byte: `00` выключить push,
  `01` включить;
- `03:45 GetPushGpsSnr` — FC → app, асинхронный поток SNR, а не запрос чтения.

Legacy parser принимает до 64 bytes:

| Offset | Количество | Значение |
|---:|---:|---|
| 0 | до 32 | SNR первого GNSS-набора |
| 32 | до 32 | SNR GLONASS-набора |

Для отображаемого значения используется `raw & 0x7F`. Ненулевой raw byte
считается присутствующим/используемым каналом. Значение bit 7 parser не
раскрывает, поэтому трактовать его без wire capture не следует.

`03:46` не включает и не выключает сам GNSS-приёмник. Он управляет только
выдачей подробной SNR-диагностики из FC. Современный native-layer подтверждает
тот же request как `uav_fc_switch_gps_snr_push_req` и key
`GPSSNRInfoPushEnable`.

### `03:93` — `SendGpsInfo`

`DataFlycSendGpsInfo` отправляет 20 bytes little-endian:

| Offset | Size | Поле | Кодирование |
|---:|---:|---|---|
| 0 | 8 | latitude | IEEE-754 `double` |
| 8 | 8 | longitude | IEEE-754 `double` |
| 16 | 2 | altitude | signed `int16` |
| 18 | 2 | heading | signed `int16` |

Ответ содержит как минимум однобайтовый `result` в offset 0. Масштаб и единицы
`altitude`/`heading` в Java-классе не подписаны, поэтому считать их метрами и
градусами без живого эталонного пакета нельзя.

По форме это координаты внешнего источника — пульта/приложения, а не сырые
данные спутников. На связь с legacy dynamic Home Point указывают набор полей и
современный key `RcDynamicHomePointSendGPS`, однако прямой Java caller для
`03:93` в 1.21.10 отсутствует. Современная реализация уже использует другой
пакет `03:CB` и передаёт 60 bytes: timestamp, latitude, longitude, altitude,
horizontal speed, horizontal accuracy, HDOP и satellite count. Поэтому
назначение `03:93` как старого dynamic-home сообщения — `DERIVED`, но не
подтверждённый live-путь Avata 360.

### `03:B5/B6` — Fault Injection Tool

Это не чтение ошибок FC. `03:B5 FaultInject` намеренно просит firmware
смоделировать отказ выбранной системы/подсистемы для проверки FDI и safety
logic. Payload всегда имеет 32 bytes:

| Offset | Size | Поле | Кодирование/значение |
|---:|---:|---|---|
| 0 | 4 | version | `uint32`, default `1` |
| 4 | 2 | message length | `uint16`, default `32` |
| 6 | 1 | command | `1 STOP`, `2 OPEN`, `3 SEND` |
| 7 | 1 | system ID | target system |
| 8 | 1 | module type | target module class |
| 9 | 1 | module index | экземпляр модуля |
| 10 | 1 | inject method | способ внесения отказа |
| 11 | 1 | fault type | тип отказа |
| 12 | 4 | fault level | IEEE-754 `float` |
| 16 | 4 | start time | IEEE-754 `float` |
| 20 | 4 | last time | IEEE-754 `float` |
| 24 | 4 | break time | IEEE-754 `float` |
| 28 | 4 | repeat count | `uint32` |

Все multi-byte fields little-endian. ACK `03:B5` содержит однобайтовый result.
`03:B6 GetPushFaultInject` — входящий однобайтовый status push:

| Codes | Статусы |
|---|---|
| 1–6 | version mismatch, open failed/success, close success, inject success/failed |
| 7–8 | FDI detect success/failed |
| 9–13 | auto-stop или deny: unsafe, existing fault, disconnect |
| 14–18 | unknown fault/system/module/command или module not found |
| 19–23 | unsupported, not opened, function closed, bad length, route failed |

В этом контексте `FDI` — **Fault Detection and Isolation**: логика обнаружения
и локализации неисправности. Префикс `FIT` в именах статусов относится к
fault-injection test/tool layer.

Таблицы допустимых `system_id`, `module_type`, `inject_method` и `fault_type` в
проверенном Java corpus нет, живых callers также нет. Поэтому `03:B5` нельзя
отправлять перебором: даже на земле она может перевести FC или связанный модуль
в искусственное fault-состояние. Без известной пары OPEN/SEND/STOP, сервисной
таблицы и способа подтвердить восстановление команда считается опасной.

## Другие интересные internal/service функции

Ниже перечислены не просто строковые имена: для первых групп в
`libsdk_jni.so` присутствуют реальные handlers и request serializers. Но общий
SDK обслуживает много моделей, поэтому поддержка Avata 360 всё равно требует
capability query или безопасного read-only запроса.

### System Self Diagnostic (`cmd_set=0x59`)

Это наиболее интересная отдельная диагностическая подсистема:

| DUML | Native request | Назначение |
|---|---|---|
| `59:01` | `uav_diag_sys_diag_mode_switch_req` | `DEFAULT` / `DIAGNOSTIC` mode |
| `59:02` | `uav_diag_get_sys_diag_capability_req` | получить поддержанные parts/actions |
| `59:03` | `uav_diag_get_sys_diag_keep_alive_req` | heartbeat активной диагностики |
| `59:04` | `uav_diag_sys_diag_execute_req` | запустить выбранный тест/action |
| `59:05` | `uav_diag_sys_diag_terminate_req` | завершить процедуру |
| `59:06` | progress push (`DERIVED` из соседней command sequence) | state/progress/failure reason |
| `59:07` | `uav_diag_get_diag_result_cmd` | получить результат/файл |

Объявленные parts:

- `ESC`;
- `NOZZLE`, `PUMP`, `DELIVER`, `THROW`, `MATRIAL` — agricultural payloads;
- `UAV_SYS_DIAG_TEST_LINK`;
- `UAV_SYS_DIAG_TEST_AVIONICS`;
- `ALL`.

Объявленные ESC actions:

- `MOTOR_BEEP`;
- `MOTOR_VERY_SLOW_ROTATE`;
- `MOTOR_SLOW_ROTATE`;
- `MOTOR_STOP_ROTATE`.

`SelfDiagnosticQueryParts` принимает не `part`, а один из двух типов
capability:

- `1 = SELF_TEST` — список узлов, поддерживающих собственно проверку;
- `2 = ACTION` — список доступных узлов/actions.

ARM-disassembly `SelfDiagnosticsHelper::GetSelfDiagnosticQueryParts()`
показывает request payload длиной 3 bytes:

```text
offset 0: 01                  protocol/subcommand version
offset 1: diagnostic target  byte из состояния SelfDiagnosticsHelper
offset 2: capability         01 SELF_TEST или 02 ACTION
```

Ответ преобразуется в `SelfDiagnosticPartsInfo` с двумя массивами: `parts[]`
и `actions[]`. На SDK boundary каждый массив сериализуется как `uint32 count`,
затем `count × int32 enum`; это формат value object, а не гарантированно
побайтовый wire layout ответа. Точный raw response layout должен быть
зафиксирован первым live capture.

Progress различает `WAITING`, `PROCESSING`, `FINISH`, `FAILED` и download
failure; причины включают return failure, execute timeout, termination и
heartbeat timeout. Helper умеет поддерживать keepalive и скачивать
диагностический result file.

Безопасный следующий эксперимент — только `59:02` capability/parts query.
Для полной инвентаризации нужны два read-only запроса — сначала capability
`SELF_TEST=1`, затем `ACTION=2`.
`59:01` и особенно `59:04` меняют режим и могут вращать мотор, поэтому их не
следует отправлять до получения model-specific capability response и точного
формата payload.

#### Живой probe `59:02` на Avata 360

Проверены read-only запросы `SELF_TEST=1` и `ACTION=2`:

```text
dst=08 (DM368), cmd=59:02, payload=01 00 01 -> response E0
dst=08 (DM368), cmd=59:02, payload=01 00 02 -> response E0
```

Также selector byte перебран в ограниченном диапазоне `0..8`; полученные
ответы оставались `E0`. В legacy `Ccode` значение `0xE0=224` однозначно
означает `INVALID_CMD`. При отправке того же запроса в FC `dst=03` ответа не
получено.

`OBSERVED` verdict: transport до активного DM368 работает, но его firmware на
Avata 360 не реализует `59:02`. Это не ошибка selector и не отсутствие TCP
маршрута. Возможности `ESC/LINK/AVIONICS` существуют в общем DJI SDK, но через
этот diagnostic framework на текущем борту не перечисляются.

### Factory/System Test (`cmd_set=0x10`)

Второй, независимый тестовый framework имеет полностью реализованные native
handlers:

| DUML | Операция |
|---|---|
| `10:10` | enable/disable FSTest для `hostId` |
| `10:11` | запустить case по `hostId` и строковому имени |
| `10:12` | прочитать состояние выполняемого case |
| `10:13` | получить число test cases |
| `10:14` | получить имя/описание case по индексу |

Результат содержит `caseState`, `errorCode`, счётчики pass/fail/skip и строку
ошибки. `10:13` и `10:14` являются наиболее ценными read-only запросами:
если Avata 360 отвечает, можно перечислить встроенные заводские тесты, не
запуская их. `10:10/11` без списка и сервисной инструкции не использовать.

`hostId` здесь является не номером test case, а составным адресом целевого
DUML-host. Native handlers раскладывают его так:

```text
device_index = hostId & 0x07
device_type  = hostId >> 3
```

и помещают результат в destination routing fields. Поэтому Java default
`hostId=0` нельзя автоматически считать адресом FC. Для обычного FLYC
`device_type=3`, `device_index=0` кандидат равен `hostId=0x18`, но это пока
`DERIVED`: перед live запросом адрес надо подтвердить по штатному request или
resolver конкретного продукта.

Wire requests:

- `10:13`: destination берётся из `hostId`; payload — однобайтовая нулевая
  request structure; response конвертируется в целое `case count`;
- `10:14`: destination берётся из `hostId`; payload — `uint32 LE index`;
  response конвертируется в строку имени/описания case.

Таким образом, безопасная процедура перечисления — получить count через
`10:13`, затем читать `10:14` только для `index=0..count-1`. Enable `10:10` для
этого по native flow не требуется. Запуск выполняет отдельная команда `10:11`.

#### Живой probe FSTest на Avata 360

`10:13` с нулевой request structure отправлен в известные бортовые device
types: camera, FC, gimbal, center, DM368, OSD/OFDM, battery, digital, FPGA,
GPS и blackbox. Наблюдалось:

- camera `dst=01` -> `E0 INVALID_CMD`;
- DM368 `dst=08` -> `E0 INVALID_CMD`;
- FC `dst=03` и остальные проверенные endpoints -> ответа нет.

Дополнительно camera получила `10:14`, payload `00 00 00 00` (index 0), и
также вернула `E0 INVALID_CMD`. PC endpoint `dst=0A` не ответил.

Aircraft `/etc/perception/json/fstest.json` и пассивный traffic определили
точный host: type `18`, index `4`, SDK `hostId=0x94`, raw DUSS address
`0x92`. `dji_autoflight` подтверждает descriptors `10:10/11/12` для этого
host. На `dst=92` отправлена последовательность enable -> count -> disable;
ACK не получены; disable затем повторён ещё три раза. Read-only `10:12` для
`CaseAutoTakeOff` также остался без ответа. `10:11 run case` не отправлялся.

`OBSERVED` verdict: FSTest реализован внутри WA530 `dji_autoflight`, но его
internal host не доступен через текущий shared RC2 injection route либо закрыт
production policy/config. Подробный разбор и product test plan вынесены в
`DJI_AVATA360_INTERNAL_TESTS.md`.

### Прямое управление отдельным мотором

Keys `StartMotorControl` и `StopMotorControl` имеют native handlers и
структурированные параметры:

- `motorIndex`: `MOTOR_1..MOTOR_4`;
- `runPwm`: заданный PWM;
- `runTime`: длительность;
- stop принимает индекс одного мотора.

В native-layer есть две реализации:

1. `OldStartMotorControlAction` принимает только `motorIndex=1` и передаёт
   `runPwm/runTime` в старый command-handler path.
2. Текущий `StartMotorControlAction` проверяет диапазон `1..4`, выбирает
   отдельное имя `set_motor_auto_start_N_0`, затем вызывает
   `SetMotorPWM()` и `SetMotorRunTime()`.

В каталоге самой Avata 360 присутствуют сокращённые варианты этих полей:

| Index | Name | Type/range | Default |
|---:|---|---|---:|
| 1183–1186 | `set_motor_auto_start_1..4` | `U32`, `0..0xFFFFFFFF` | 0 |
| 1473 | `user_test_std_output` | `U16`, `0..10000` | 500 |
| 1474 | `user_test_timer` | `U16`, `0..65535` | 20 |
| 1475 | `user_test_esc_factory_test` | `U8`, `0..1` | 0 |

Это сильный признак, что необходимые FC primitives присутствуют именно в
таблице Avata 360, а не только в общем SDK. Старый native path масштабирует
`runPwm × 100`, что согласуется с диапазоном `user_test_std_output=0..10000`:
вероятный внешний диапазон `runPwm` — `0..100`. Единицы `runTime` статически
не подписаны и остаются неизвестными.

Рядом подтверждён request type `03:E9 uav_fc_set_cmd_handler_req`; связь
полного современного action с одним одиночным `03:E9` пока `DERIVED`, потому
что action также выполняет несколько config writes. Это не простая команда
`motorIndex + PWM + time` одним пакетом.

Это принципиально отличается от безопасного ESC beep: команда предназначена
для физического вращения выбранного мотора. До снятия пропеллеров, определения
точной последовательности записей, единиц времени и гарантированного
stop/timeout её не тестировать. Даже `runPwm=0` не использовать как probe:
action может отдельно включить `user_test_esc_factory_test` или auto-start
flag до применения PWM.

#### Состояние motor-test параметров Avata 360

Сохранённый полный live dump и контрольное чтение после probe показывают:

```text
set_motor_auto_start_1 = 0   (live E2 readback подтверждён повторно)
set_motor_auto_start_2 = 0
set_motor_auto_start_3 = 0
set_motor_auto_start_4 = 0
user_test_std_output   = 500
user_test_timer        = 20
user_test_esc_factory_test = 0
```

Все значения совпадали с defaults, `changed=false`. В текущем сеансе записи
этих параметров и `StartMotorControl`/`StopMotorControl` не выполнялись.

### Display/showroom mode

Присутствуют `DisplayModeSwitch`, `FCTurnOnDisplayMode`,
`FCTurnOffDisplayMode` и `IsInDisplayMode`. Результаты различают success,
already in/not in display mode и `MOTOR_ON`. Для части изделий используется
`FLYC2 22:60`. Это сервисный/showroom режим FC, а не настройка дисплея пульта.
Его реальный эффект model-specific и без необходимости включать его не нужно.

### C0 Height Limit unlock/sync

Keys `QueryC0HeightLimitState`, `SyncC0HeightLimitState` и
`QueryC0HeightLimitStateIsSupport` имеют состояния `LIMIT_HEIGHT` и
`UNLIMIT_HEIGHT`. Native strings раскрывают полный контекст:

- `IsDroneC0HeightLimitUnlockSupport`;
- `UnlockC0HeightLimit` network response;
- `uav_adsb_get_realname_check_protocol_rsp`;
- `DroneC0HeightLimitState`.

Это сетевой/регуляторный C0 + real-name unlock flow, а не обнаруженный ранее
30-метровый pre-Home-Point limiter. Он требует server response/message и затем
синхронизирует состояние с бортом. Простого локального boolean bypass по этим
символам не доказано.

### Blackbox, logs и calibration files

В SDK есть:

- `FCDeleteBlackBox` и `DeleteBlackBoxAction`;
- `StartExportLog`, `StopExportLog`, test-log export;
- clear flight/payload logs и запрос их status/history/space;
- `UpdateCalibrationFile` для camera/flight-assistant calibration modules;
- `SwitchToReadDataMode`, legacy `SetReadFlyDataMode` и
  `FormatDataRecorder`.

Blackbox delete, format и calibration-file update являются destructive
service actions. Наличие handler не означает, что приложение может загрузить
произвольный файл: format/signature/product compatibility остаются отдельными
гейтами.

### Authority, NFZ и identity/regulatory

Другие реализованные поверхности:

- request/release joystick control authority;
- multi-RC control lock/surpass (`cmd_set=0x19`, включая `19:40` lock right);
- common flight-restriction transfer enter/exit/check;
- transfer authorization by area ID;
- NFZ database upload/status/unlock-area serializers;
- RID registration с nonce/shared-key handshake;
- EID open/close и operator/aircraft identification fields;
- access-locker setup/login/reset/account operations;
- prototype-info update и app-state synchronization.

Эти пути смешивают aircraft, RC, goggles, enterprise и regulatory products.
Они интересны для картирования, но не являются универсальными обходами
авторизации, geofence или Remote ID: большинство требует capability,
credential/server response либо криптографический handshake.

## Что DJI Fly не делает обычной настройкой

В проверенном 1.21.10 corpus не найден пользовательский путь, который:

- загружает в flight MCU новый PID/ADRC/estimator/mixer executable;
- заменяет navigation или obstacle-avoidance algorithm своим кодом;
- компилирует/инжектирует произвольный control loop;
- выключает питание GNSS receiver;
- универсально снимает все app/cloud/firmware geofence и height layers одной
  DUML-командой;
- гарантирует поддержку любого SDK key на любом aircraft.

Firmware update действительно способен заменить исполняемый код модулей, но
это отдельный OTA/firmware flow с package verification, compatibility и
подписями. Его нельзя смешивать с «изменением настройки».

## Legacy, developer и internal возможности

В APK присутствуют дополнительные поверхности, которые нельзя автоматически
считать доступными пользователю:

- classic `uav.midware` с сотнями DUML model classes;
- developer settings/tools;
- `InnerTools` с command watch, key dump, raw recording, ADB/TCP datalink и
  mobile joystick;
- simulator и fault-injection classes;
- NFZ upload/update serializers;
- test/display/motor-control actions;
- enterprise/agricultural keys.

Часть закрыта product key, region, firmware capability, debug/native flag или
не имеет живых Java callers. `OBSERVED` наличие кода; `HYPOTHESIS` доступность
на серийном consumer aircraft без отдельной live-проверки.

## Request/response и push telemetry

DJI Fly не только пишет настройки. Основной runtime — непрерывные pushes:

- aircraft position, velocity, attitude, mode, motor/flying state;
- GPS validity, satellite count, signal/interference/spoof state;
- battery/RTH/landing assessment;
- obstacle/vision/navigation status;
- IMU/compass/ESC diagnostics;
- mission progress и error state;
- flight logs/record chunks.

Живые RC2 captures уже показывали `03:43` GNSS/OSD, `03:44` home-point state
и `03:D7` flight-record chunks. В native ELF присутствуют отдельные
`Key*Push` handlers, которые превращают DUML pushes в key-value cache. Поэтому
полный обмен — это цикл `set/action -> ACK`, затем независимые state pushes и
UI readback, а не только одиночные request/response.

На RC Pro 2 router log DJI Fly работал в slot `0x0205`. Одновременное открытие
сторонним приложением shared local DUML port может нарушать штатную сессию;
успешный TCP `write()` не означает, что FC получил, применил и сохранил
параметр.

## Итоговая evidence-таблица

| Claim | Level | Artifact/location | Evidence | Confidence |
|---|---|---|---|---|
| DJI Fly меняет реальные настройки FC | `OBSERVED` | UI ViewModels -> FlyModel -> KSDK | write calls для height, distance, RTH, failsafe, GNSS, control feel | High |
| DJI Fly запускает state-machine actions | `OBSERVED` | `UAVFlightControllerKey` | takeoff, landing, RTH, calibration, virtual stick и др. | High |
| Intelligent flight распределён между FC/vision/navigation | `OBSERVED` | native command templates + FlightAssistant keys | command sets `03`, `0A`, `23` и отдельные push handlers | High |
| Обычный settings UI заменяет flight algorithm | `NEGATIVE` | проверенный 1.21.10 corpus | найден parameter/action/OTA flow, но не code-injection setting | High для этого APK |
| APK содержит низкоуровневый parameter writer | `OBSERVED` | `DataFlycSetParams`, `libsdk_jni.so` | `03:F9 uav_fc_set_write_hash_param_req` | High |
| Любой из 687 локальных parameters работает на Air 3S/Avata | `HYPOTHESIS` | `flyc_param_infos` vs live model table | локальный каталог отличается от live FC table | Low без теста |
| Key с `S` работает на любом DJI drone | `NEGATIVE` | product-specific resolver/capability gates | общий SDK обслуживает разные product families | High |

## Следующий эксперимент с максимальной отдачей

На живой Avata 360 уже выполнены bounded Home Point, ESC beep, diagnostic и
FSTest probes через Termux на RC2. Порт `40007` использовался только на время
capture и после каждой операции закрывался.

Лучший следующий тест — получить DUSS IPC непосредственно внутри aircraft
Linux либо вызвать штатный KSDK action из процесса DJI Fly, параллельно включив
`InnerTools.setCmdWatchCallback`/key dump или router-level capture:

1. записать product/firmware и исходное значение;
2. снять key name, destination, `cmd_set:cmd_id` и payload;
3. получить ACK и независимый state push/readback;
4. переподключить aircraft, затем power-cycle;
5. отметить runtime/persistent/rejected и физический эффект.

Такой capture превратит общую SDK-карту в точную model-specific таблицу
`UI -> key -> wire -> receiver -> persistence`. Для FSTest точный host уже
определён как type `18`, index `4` (`raw=0x92`), но shared RC2 injection route
не вернул ACK даже на read-only enumeration.

## Связанные документы

- [Avata 360: internal diagnostics и factory tests](DJI_AVATA360_INTERNAL_TESTS.md)
- [Home Point и ограничение высоты без GNSS](DJI_HOMEPOINT_HEIGHT_LIMIT.md)
- [DJI Fly: модель и серийник](DJI_FLY_APK_IDENTITY_MAP.md)
- [Таблица параметров FC](FLYC_PARAM_TABLE.md)
- [Карта DUML stream](DUML_STREAM_MAP.md)
- [RC2 router log](RC2_DJILINK_ROUTER_LOG.md)
- [Аудит DUML-команд](DUML_COMMAND_AUDIT.md)
- [Локальный firmware corpus](FIRMWARE_CORPUS.md)
