# RC Pro 2: справочник DUML/DUSS command set `0x51`

Дата фиксации: 2026-07-24.

Справочник построен только по ранее скачанным пользователем RC Pro 2 firmware:

- `V55.31.01.39/139`, `dji_wlm` Build ID
  `41970d8e26ecf0edee6c78f4f9f7f5d7`;
- `V55.31.05.76/576`, `dji_wlm` Build ID
  `a0a736c567c361bdd1568aac3ac99722`.

Полные SHA-256 и границы корпуса находятся в
[`FIRMWARE_CORPUS.md`](FIRMWARE_CORPUS.md). Набор активных `0x51` handlers
совпадает в обеих версиях.

## Адреса модулей

| Config field | Host ID | Модуль |
|---|---:|---|
| `local_router_host_id` | `0x0205` | локальный Android DUSS router |
| `service_module_id` | `0x0E06` | `dji_lte` |
| `local_wlm_host_id` | `0x0E07` | наземный `dji_wlm` |
| `peer_wlm_host_id` | `0x0907` | воздушный WLM peer |

Abstract socket `/duss/mb/0x205` подключает клиента к local router. В профиле
FreeFCC destination `0xEE` соответствует `vt_gnd:7` и маршрутизируется в
`dji_wlm`; само имя socket не означает LTE module.

## Таблица `dji_wlm`

`dji_wlm` регистрирует command set `0x51` с count `0x52`. Допустимый диапазон
таблицы — `0x00..0x51`. Пустые ID внутри диапазона не имеют handler; ID
`0x52..0xFF` находятся за границей таблицы.

| ID | Request handler | Ack/response handler | Установленный смысл |
|---:|---|---|---|
| `01` | `wlm_process_forward_pkt` | — | Forward packet |
| `02` | `wlm_link_mode_sw_trigger` | — | Trigger link-mode switch |
| `03` | `wlm_link_status_report` | — | Link status report |
| `05` | — | `wlm_route_switch_ack` | Route-switch ACK |
| `06` | `wlm_link_sw_res_sync` | `wlm_link_sw_res_ack` | Link-switch result sync/ACK |
| `07` | — | `wlm_link_ctrl_ack` | Link-control ACK |
| `08` | `wlm_link_sw_nego_res_proc` | `wlm_link_sw_nego_ack` | Link-switch negotiation result |
| `09` | debug tools request | debug tools ack | Debug tools exchange |
| `0A` | `wlm_link_mode_query` | — | Query link mode |
| `0D` | debug report control | — | Debug reporting |
| `0F` | route-switch request | — | Request route switch |
| `10` | video unsmooth level | — | Video quality/unsmooth metric |
| `15` | select target device | — | Target peer selection |
| `18` | receive video status | — | Receive-video state |
| `19` | `wlm_modem_onoff_control` | — | Modem on/off control |
| `1A` | `wlm_service_mode_switch_req` | — | Service mode/link selection |
| `1B` | power-control agent report | — | Power-control report |
| `1D` | — | power-control ack | Power-control ACK |
| `1E` | local frequency info request | — | Local frequency information |
| `1F` | — | local frequency info ack | Frequency info ACK |
| `20` | product connection state | — | Product connection state |
| `21` | auto tools request | auto tools ack | Automatic diagnostic tools |
| `22` | `wlm_bind_status_changed` | — | Bind-status change |
| `23` | query status | — | Status query |
| `24` | — | agent test ack | Test ACK |
| `27` | RTT analysis | — | Round-trip-time analysis |
| `29` | TLV agent report | — | TLV report |
| `2A` | special link report | — | Special-link state |
| `2C` | bandwidth attach | — | Bandwidth attachment |
| `2E` | netlink request | netlink response | Netlink exchange |
| `2F` | general control request | — | General control |
| `34` | neighbor info request | — | Neighbor information |
| `41` | ability negotiation request | ability negotiation ack | Capability negotiation |
| `42` | ability result request | ability result ack | Capability result |
| `51` | v3 forward | — | Version-3 forwarding |

Имена отражают symbols/handlers в ELF. Там, где layout payload полностью не
восстановлен, имя handler подтверждает семейство операции, но не даёт
безопасного готового payload.

## Таблица `dji_lte`

`dji_lte` также регистрирует `0x51` с count `0x52`, но его собственная таблица
значительно разреженнее:

| ID | Handler | Смысл |
|---:|---|---|
| `01` | `lte_wl_manage_forward_pkt` | WLM/LTE packet forward |
| `07` | `lte_link_ctrl_proc_gnd` | Ground link control |
| `0D` | `lte_debug_rpt_ctrl` | LTE debug report control |
| `30` | `wlm_dev_mid_allocate_5130` | Device module-ID allocation |
| `33` | `wlm_dev_route_info_sync_5133` | Device route information sync |
| `41` | ability negotiation request/ack | Capability negotiation |
| `42` | ability result request/ack | Capability result |
| `51` | `lte_wl_manage_forward_pkt_v3` | Version-3 WLM/LTE forward |

Наличие handler в `dji_lte` не означает, что произвольный кадр, адресованный
`0xEE`, автоматически попадёт туда: сначала применяется DUSS route и логика
`dji_wlm`.

## Точно разобранные payload-ограничения

### `51:1A` — service mode switch

Текущий FreeFCC payload: `00 00 00 + ASCII(identity)`.

| Offset | Значение | Интерпретация handler |
|---:|---:|---|
| `0` | `00` | `SERVICE_LIVEVIEW` |
| `1` | `00` | liveview branch |
| `2` | `00` | `LIVEVIEW_SDR` |
| `3..` | identity | поиск peer через `wlm_peer_dev_list_find` |

Enums из `libwlm.so`:

- service: `SERVICE_LIVEVIEW=0`, `SERVICE_DOWNLOAD=1`;
- liveview: `LIVEVIEW_SDR=0`, `LIVEVIEW_HYBIRD=1`,
  `LIVEVIEW_WIFI=2`;
- download: `DOWNLOAD_COMMON=0`, `DOWNLOAD_WIFI_HIGHSPEED=1`.

Handler может вызвать `wlm_link_mode_sw_trigger`, но только после своих
внутренних проверок:

- `service_type` допускает только `0..1`;
- liveview допускает mode `0..2`, download — `0..1`;
- при уже идущем переключении повтор с теми же sender/sequence игнорируется,
  а конкурирующий запрос получает ответ `5/5/5`;
- пустой peer list и identity, которой нет в peer list, дают ответ `7/7/7`;
- при принятом запросе результат приходит асинхронно после
  `wlm_link_mode_sw_trigger`.

Для `service_type=0`, `mode=0` handler формирует SDR-only link modes. Поэтому
FreeFCC посылает корректно структурированный запрос перевода liveview в SDR,
но не запрос LTE activation. Если peer уже работает в SDR-only или identity не
найдена, видимого изменения не будет. Именно такой отрицательный live-результат
получен при отправке полного sweep.

### `51:19` — modem on/off

В обеих сборках handler требует payload **не короче восьми bytes**. Первые
четыре поля имеют такой layout:

| Offset | Поле | Допустимые значения в handler | Значение FreeFCC |
|---:|---|---|---|
| `0` | `msg_ver` | кэшируется/логируется | `00` |
| `1` | `link_type` | `0` для поддержанного SDR path | `00` |
| `2` | `control_type` | `2` — power-on path, `3` — power-off path | `00` |
| `3` | `cmd_type` | `0` — control request, `1` — agent report | первый ASCII-байт serial |

Следовательно, body `00 00 00 + ASCII(serial)` проходит проверку длины, но
первый символ serial становится `cmd_type`. Для обычного serial это не `0` и
не `1`: handler пишет `error cmd_type` и завершает обработку без power-control
действия. Даже гипотетический четвёртый нулевой byte оставил бы
`control_type=0`, который handler помечает как неподдерживаемый.

Реальные control branches здесь существуют, но FreeFCC их не выбирает:
`cmd_type=0/control_type=2` ставит событие включения SDR modem, а
`cmd_type=0/control_type=3` после precondition ставит событие выключения.
`cmd_type=1` — обратный report о фактическом SDR power state, а не команда
включения LTE.

### `51:22` — bind status changed

Handler сначала пересылает исходное событие LTE-службе, затем копирует до
шести bytes payload и подтверждает его трёхбайтовым нулевым ACK. Непосредственно
разобраны первые три поля:

| Offset | Поле | Значения |
|---:|---|---|
| `0` | `version` | версия отчёта |
| `1` | `status` | `0` unknown, `1` start, `2` success, `3` failed, `4` cancel |
| `2` | `scene` | context; значение `2` пропускает обычное обновление bind flag |
| `3..5` | reserved/context | главным handler напрямую не интерпретируются |

`status=1` поднимает внутренний bind flag, `0/3/4` его снимают. Ветка
`status=2` проверяет текущие link modes: при необходимости запускает route
control, сбрасывает LTE agent-report cache, а completion callback обновляет
session/device state и повторно включает frequency auto-avoid. Это внутренний
отчёт о ходе binding, а не самостоятельная activation-команда.

FreeFCC снова посылает `00 00 00 + ASCII(serial)`, то есть
`version=0/status=unknown/scene=0`. В этой ветке serial не используется как
identity, bind flag снимается, а LTE activation не запускается.

Контракты `51:19`, `51:1A` и `51:22` совпадают в RC Pro 2 build 139 и 576.
Адреса ELF-symbols:

| Handler | build 139 | build 576 |
|---|---:|---:|
| `wlm_bind_status_changed` | `0x117960` | `0x133f28` |
| `wlm_service_mode_switch_req` | `0x139608` | `0x15c9d8` |
| `wlm_modem_onoff_control` | `0x15ed64` | `0x185758` |

### `51:14` — наблюдаемый push в другом корпусе

В RM510 `dji_wlm` исходящий `51:14` — neighbor/device-link list формата
`2 + 49 × N`. В live capture payload длиной 51 byte соответствовал одному peer
и содержал полный aircraft serial. Это объясняет, почему FreeFCC может извлечь
identity из `51:14`, но не делает входящий sweep с тем же command set
активацией: направление и contract payload различаются.

## Вывод для sweep `51:00..7F`

| Группа | Количество | Результат |
|---|---:|---|
| ID с активным WLM handler | 35 | Смешанные link, route, debug, bind, frequency и forwarding операции |
| Пустые slots внутри `00..51` | 47 | Нет зарегистрированного handler |
| ID вне таблицы `52..7F` | 46 | Framework не может выбрать handler из этой registration table |
| Весь sweep в пользовательском тесте | 128 | Видимого эффекта не наблюдалось; ACK/state capture отсутствует |

Таким образом, профиль нельзя отождествлять со штатным eSIM flow DJI Fly
`18:4B/4C`. Одновременно live-результат не подтверждает опасность: на
проверенной связке sweep не дал видимого эффекта. Для точного объяснения
потребовался бы capture внутренних ACK/log/state во время отправки, то есть
новая реальная проверка, не входящая в текущий offline-анализ.
