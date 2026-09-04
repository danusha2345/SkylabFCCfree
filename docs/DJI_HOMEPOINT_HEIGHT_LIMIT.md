# DJI Home Point и ограничение высоты без GNSS

Дата проверки: 2026-09-03.

## Вопрос

Есть ли записываемый параметр, который задаёт потолок до фиксации Home Point,
и можно ли принудительно записать Home Point без спутников?

## Живой тест `03:31`

Стенд:

- DJI RC2, Termux `uid=10028`;
- Vitya RC2 / DJI Fly 1.21.10;
- связанный борт DJI Avata 360;
- дрон на земле, GNSS position ещё не готова;
- raw inject через `127.0.0.1:40008`, ответ перехвачен коротким окном на
  shared `40007`.

Отправлен один `SetHomePoint`:

```text
TX 55 1f 04 4e 02 03 31 4a 40 03 31
   00 0000000000000000 0000000000000000 00 90 30
```

Payload:

```text
type=00 AIRCRAFT
latitude=0.0
longitude=0.0
interval=0
```

Получен CRC-valid matching ACK с тем же sequence `0x4a31`:

```text
RX src=0x03 dst=0x02 seq=0x4a31 attr=0x80 cmd=03:31 payload=01 03
```

`OBSERVED`: FC принял команду на уровне transport и вернул отказ. Home Point
не записана.

`DERIVED`: второй status `03` соответствует `GPS_NOT_READY`. Такой порядок
совпадает с DJI SDK error family для Set Home Location:

```text
01 invalid GPS coordinate
02 initial Home Point not recorded
03 GPS not ready
05 distance too far
```

В DJI Fly 1.21.10 этому соответствует
`UpdateHomePointError.GPS_NOT_READY`: «слабый GPS дрона, обновление Home Point
не удалось». Следовательно, обход UI прямым `03:31` **не обходит проверку FC**.

## Минимальное число спутников

В legacy-таблице DJI Fly действительно есть:

| Параметр | Тип | min | max | default |
|---|---:|---:|---:|---:|
| `g_config.gps_cfg.gps_fix_num` | `u8` | 6 | 32 | 8 |

Но этот параметр отсутствует в выгруженных современных model-specific
таблицах Air 3, Air 3S, Mini 4 Pro, Mini 5 Pro, Mavic 4, Neo/Neo 2 и Avata
360. Там видны только runtime satellite counts (`imu*.gps_svn`), FDI switches
`gps_fdi_open_svn_exception`/`gps_fdi_open_level_low` и simulator-only
`simulator_gps_svn`.

`NEGATIVE`: для современных проверенных моделей не найден отдельный
записываемый параметр «минимум N спутников для Home Point».

Это подтверждено live на Avata 360 через оба известных имени:

| Имя | `03:F7` | `03:F8` | Verdict |
|---|---|---|---|
| `g_config.gps_cfg.gps_fix_num` | status-only `03` | status-only `00` | отсутствует |
| `gps_fix_num` | status-only `03` | ответа с echoed hash/value нет | отсутствует |

Hash соответственно `2ca6163a` и `d8bf0b80` на проводе. Ни один ответ не
содержал metadata или значение параметра, поэтому `03:F9` по этим адресам
писать нечего.

`DERIVED`: современный FC принимает решение по внутреннему GPS
level/validity/fusion state, а не по одному открытому порогу satellite count.
В SDK отдельно существуют `SatelliteCount`, `GPSSignalLevel`, `GPSIsValid` и
ошибка `GPS_NOT_READY`.

### Современные writable FDI-кандидаты Avata 360

На том же борту live `03:F7/F8` подтвердили:

| Параметр | Hash LE | type/size | attribute | min..max/default | current |
|---|---|---|---:|---|---:|
| `gps_fdi_open_svn_exception` | `576c7ebe` | `u8/1` | 3 | `0..1 / 1` | 1 |
| `gps_fdi_open_level_low` | `f461e2d0` | `u8/1` | 3 | `0..1 / 1` | 1 |
| `g_config.fdi_open.without_gps_allowed` | `0efb60ab` | `u8/1` | 3 | `0..1 / 0` | 0 |

`attribute=3` означает EEPROM read/write. Short alias
`without_gps_allowed` (`511cd24d`) на этом борту отсутствует.

Это реальные записываемые параметры, но их названия описывают FDI policy:

- `svn_exception` — учитывать аномалию числа спутников;
- `level_low` — учитывать низкий GPS level;
- `without_gps_allowed` — разрешить FDI-сценарий без GPS.

### Live A/B трёх FDI-флагов

Все три кандидата проверены на включённой Avata 360, на земле. Для каждого
изменённое значение подтверждалось через `03:F8`, после теста исходное значение
восстанавливалось через `03:F9` и снова проверялось через `03:F8`.

1. `without_gps_allowed`: `0 -> 1`, readback `1`, затем `03:31` вернул
   `01 03 = GPS_NOT_READY`; восстановлен в `0`, readback `0`.
2. `gps_fdi_open_svn_exception`: `1 -> 0`, readback `0`, затем восстановлен
   в `1`.
3. `gps_fdi_open_level_low`: `1 -> 0`, readback `0`, затем восстановлен в
   `1`.
4. При одновременном `gps_fdi_open_svn_exception=0` и
   `gps_fdi_open_level_low=0` два CRC-valid ACK `03:31` снова вернули
   `01 03`. Оба флага восстановлены в `1` и подтверждены readback.

Некоторые ACK терялись из-за нестабильного shared capture, поэтому результат
считался только при наличии matching sequence и последующего readback. Home
Point ни в одном варианте не была записана.

`NEGATIVE`: эти параметры управляют FDI policy/reporting, но не создают
валидную GNSS position и не снимают проверку `GPS_NOT_READY` в обработчике
`SetHomePoint`.

## Кандидаты высотного ограничения

Новые model-specific таблицы содержат следующий стек:

| Параметр | Роль | Air 3S default/range |
|---|---|---|
| `g_config.flying_limit.max_height` | основной пользовательский предел | `120`, `20..500` |
| `g_config.flying_limit.user_set_max_height` | сохранённый выбор пользователя | `120`, `20..65535` |
| `fscap_max_height` | capability/policy ceiling | `120`, `20..65535` |
| `limit_height_rel` | относительный динамический предел | `5`, `0..30` |
| `limit_height_rel_by_light` | предел при light/vision condition | `3`, `0..30` |
| `limit_height_rel_dynamic_increment` | добавка к относительному пределу | `0`, `0..10` |
| `g_config.fdi_open.without_gps_allowed` | разрешение FDI-сценария без GPS | `0`, `0..1` |
| `novice_func_enabled` / `ap_fl_novice_mode_enable` | novice-mode gates | `0`, `0..1` |

Старый общий каталог дополнительно содержит
`g_config.novice_cfg.max_height=30`. Это действительно 30-метровое значение,
но оно относится к **novice mode**, а не доказанно к отсутствию Home Point.

На современных моделях отдельного `novice_cfg.max_height` нет. При этом
`limit_height_rel` имеет **максимально допустимое** значение 30, но его
заводское значение равно 5 м; `limit_height_rel_by_light` — 2–3 м. Поэтому
число `30` в metadata нельзя автоматически считать активным потолком без GNSS.

## Runtime limit — отдельный слой

DJI Fly получает от борта read-only runtime keys:

- `FlightLimitHeight`;
- `LimitMaxFlightHeightInMeter`;
- `AccurateLimitHeight`;
- `HeightLimitReason`.

`HeightLimitReason` явно различает:

- `NO_GPS_LIMIT = 1`;
- `NOVICE_MODE = 7`;
- `INVALID_REF_HEIGHT = 11`;
- `POOR_GPS_AT_NIGHT = 14`;
- `GPS_LEVEL_NEVER_BE_GOOD_AND_NO_APP_POSE = 15`.

Это показывает, что фактический потолок вычисляется state machine FC. Он может
быть результатом GPS/Home Point/vision/light state и не обязан храниться как
один writable parameter со значением 30.

## Вердикт

| Claim | Level | Verdict |
|---|---|---|
| Прямой `03:31` записывает Home Point без GNSS | `NEGATIVE` | FC ответил `01 03`, Home Point не записана |
| Есть legacy minimum satellite parameter | `OBSERVED` | `gps_fix_num`, default 8 |
| `gps_fix_num` есть на современных проверенных моделях | `NEGATIVE` | в model-specific таблицах отсутствует |
| FDI-кандидаты доступны для записи на Avata 360 | `OBSERVED` | три live `03:F7/F8`, attribute 3 |
| `without_gps_allowed=1` разрешает Home Point без GNSS | `NEGATIVE` | подтверждённый readback `1`, `03:31 -> 01 03`, восстановлен `0` |
| Отключение обоих GPS-FDI gate разрешает Home Point | `NEGATIVE` | оба readback `0`, два `03:31 -> 01 03`, оба восстановлены `1` |
| `novice_cfg.max_height=30` задаёт no-Home-Point ceiling | `HYPOTHESIS` | число есть только в legacy novice group |
| No-GPS ceiling является одним writable параметром | `NEGATIVE` | runtime имеет отдельный reason и effective-limit pushes |
| `limit_height_rel` участвует в динамическом потолке | `HYPOTHESIS` | сильный кандидат, но default 5, не 30; нужен A/B |

Главный вывод: **подтверждённого параметра «30 м до Home Point» пока нет**.
Наиболее вероятно, это runtime ceiling FC с причиной `NO_GPS_LIMIT`, а не
обычный `max_height`. Записывать `max_height=500` недостаточно: state machine
всё равно может вернуть более низкий `LimitMaxFlightHeightInMeter`.

## Следующий безопасный A/B

Следующий тест нужно делать внутри Vitya/SDK, не удерживая shared `40007`:

1. вывести на экран/в plaintext log `SatelliteCount`, `GPSIsValid`,
   `HeightLimitReason` и `LimitMaxFlightHeightInMeter`;
2. снять значения до первой Home Point;
3. дождаться штатной Home Point и снять те же значения;
4. сравнить runtime limit до и после штатной Home Point;
5. только затем, при необходимости, отдельно проверить `limit_height_rel`
   read/write/readback с восстановлением исходного значения.

Перебор остальных FDI-флагов для обхода Home Point больше не обоснован:
решающий reject остаётся в GNSS-validity state FC.

Это даст точный переход
`NO_GPS_LIMIT -> NORMAL` и покажет реальный потолок до/после Home Point без
небезопасного перебора коэффициентов.

## Связанные документы

- [DJI Fly и полётный контроллер](DJI_FLY_FLIGHT_CONTROLLER_INTERACTION.md)
- [Таблица параметров FC](FLYC_PARAM_TABLE.md)
- [Карта DUML stream](DUML_STREAM_MAP.md)
