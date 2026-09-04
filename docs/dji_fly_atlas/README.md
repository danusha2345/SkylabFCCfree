# DJI Fly Native Command and Capability Atlas

Это канонический индекс команд, функций, параметров, реализаций и live
evidence DJI Fly. Он дополняет подробные Markdown-разборы, но не заменяет их:
каждая существенная запись должна ссылаться на исходник, firmware artifact,
capture или документ с проверенным evidence.

## Где что хранится

- `schema.sql` — нормализованная схема SQLite;
- `seed.sql` — воспроизводимые начальные записи;
- `dji_fly_atlas.sqlite` — локальная рабочая база, генерируется и не хранится
  в Git;
- `exports/commands.jsonl` — стабильный каталог команд для diff/search;
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
`PRAGMA integrity_check` вместе с `foreign_key_check`.

## Модель данных

- `commands` — уникальная пара `cmd_set/cmd_id`, направление и риск;
- `routes` — sender/receiver, включая внутренние DUSS hosts;
- `payload_fields` — request/response/push layout;
- `implementations` — UI, SDK key, Java, native и firmware symbols;
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

Следующие партии: остальные FC getters/setters, flight-assistant actions,
camera/gimbal commands, product/version matrix и автоматический импорт таблиц
из decompiled APK.
