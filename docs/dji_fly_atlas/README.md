# DJI Fly Native Command and Capability Atlas

Это канонический индекс команд, функций, параметров, реализаций и live
evidence DJI Fly. Он дополняет подробные Markdown-разборы, но не заменяет их:
каждая существенная запись должна ссылаться на исходник, firmware artifact,
capture или документ с проверенным evidence.

## Где что хранится

- `schema.sql` — нормализованная схема SQLite;
- `seed.sql` — воспроизводимые начальные записи;
- `imports/all_cmd_ids_1.21.10.csv` — все raw `CmdSet.java` declarations;
- `imports/flyc_cmd_ids_1.21.10.csv` — отдельный воспроизводимый FLYC slice;
- `dji_fly_atlas.sqlite` — локальная рабочая база, генерируется и не хранится
  в Git;
- `exports/commands.jsonl` — стабильный каталог команд для diff/search;
- `exports/declarations.csv` и `coverage.json` — raw enum и покрытие curated
  layer;
- `exports/aliases.csv` — разные Java-имена одного wire ID;
- `exports/relationships.csv` — рёбра графа;
- `exports/atlas.dot` — Graphviz-представление цепочек.

SQLite является source of truth во время анализа. `schema.sql` и `seed.sql`
делают её воспроизводимой и проверяемой через Git. Generated exports нужны для
Claude/Codex, обычного `rg`, review и последующего HTML viewer.

## Быстрый старт

```bash
python3 tools/dji_fly_atlas.py build
python3 tools/dji_fly_atlas.py check
```

`build` идемпотентно применяет schema/seed, создаёт экспорты и запускает
`PRAGMA integrity_check` вместе с `foreign_key_check`. CSV из `imports/`
автоматически загружаются в `command_declarations`.

Повторное извлечение всех command sets из decompiled DJI Fly 1.21.10:

```bash
python3 tools/dji_fly_atlas.py extract-cmdsets \
  --source .scratch/dji-fly-fc-audit-20260903/v1.21.10/jadx/sources/uav/midware/data/config/P3/CmdSet.java \
  --version 1.21.10 \
  --constant TbsListener.ErrorCode.TPATCH_BACKUP_NOT_VALID=241 \
  --constant TbsListener.ErrorCode.TPATCH_ENABLE_EXCEPTION=242 \
  --constant IjkMediaMeta.FF_PROFILE_H264_HIGH_444_PREDICTIVE=244 \
  --constant ByteCode.ARRAYLENGTH=190 \
  --constant ByteCode.ATHROW=191
```

Для изолированного FLYC slice используется та же команда с именем
`extract-flyc-enum` и без двух `ByteCode.*` constants.

## Модель данных

- `commands` — уникальная пара `cmd_set/cmd_id`, направление и риск;
- `command_sets` — имена и номера command sets для конкретной версии Fly;
- `routes` — sender/receiver, включая внутренние DUSS hosts;
- `payload_fields` — request/response/push layout;
- `implementations` — UI, SDK key, Java, native и firmware symbols;
- `command_declarations` — дословные enum declarations, включая aliases,
  handlers, constructor flags и unresolved constants;
- `product_support` — поддержка по продукту и версии DJI Fly;
- `observations` — live results и raw response;
- `evidence` — первичные источники и проверенные документы;
- `relationships` — универсальные рёбра графа.

Canonical references в `relationships` имеют форму:

```text
cmd:03:31
param:without_gps_allowed
state:HOME_POINT
host:MVISION_4_0x92
```

Базовая цепочка уже зафиксирована:

```text
cmd:03:31 -> REQUIRES -> state:GNSS_VALID
cmd:03:31 -> UPDATES -> state:HOME_POINT
state:HOME_POINT -> AFFECTS -> state:HEIGHT_LIMIT_REASON
```

## Правила наполнения

1. Не считать наличие SDK key доказательством поддержки конкретным дроном.
   Аналогично, строка в `command_declarations` ещё не является curated
   `commands` row.
2. Разделять `observed`, `derived` и `hypothesis`.
3. Для live result сохранять response bytes и product/version.
4. Для изменяющей команды указывать риск и обратную операцию в notes/evidence.
5. Не складывать большие APK, ELF, dumps или captures в эту папку — только
   ссылки и hashes; сами артефакты остаются в `.scratch` или `~/storage`.
6. После изменения `schema.sql`/`seed.sql` запускать `build`, проверять exports
   и `git diff --check`.

## Текущий охват

Первый seed охватывает FC Home Point/name/failsafe, Home/GPS pushes, GPS SNR,
legacy GPS, fault injection, ESC echo/beep, FC parameter read/write, FSTest
`10:10..14` и System Self Diagnostic `59:02/04/05`.

Автоматический импорт DJI Fly 1.21.10 содержит 803 declarations из 24 command
sets: 779 wire entries и 24 sentinels `Other(511)`. Все IDs разрешены;
14 declarations уже связаны с curated commands. FLYC занимает 119 declarations
(118 wire entries). Актуальная статистика генерируется в
`exports/coverage.json`, полный raw слой — в `exports/declarations.csv`.
Восемь обнаруженных alias-collisions автоматически вынесены в
`exports/aliases.csv`.

Следующие партии: curated разбор CAMERA/GIMBAL/FLYC commands, payload layouts,
flight-assistant actions, product/version matrix и native handler links.
