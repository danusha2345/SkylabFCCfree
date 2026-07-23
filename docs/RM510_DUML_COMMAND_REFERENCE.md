# Справочник DUML-команд из RM510 / DJI RC2

Дата среза: 2026-07-23.

Это статический справочник по командам, которые удалось восстановить из
сохранённых userspace ELF пульта RM510, с cross-check по live DUML-потокам
FreeFCC. Это не полный каталог всего DJI protocol: часть команд формируется
динамически, часть обслуживается отдельными MCU/aircraft components, а не
Android/Linux userspace пульта.

Связанный аудит команд, которые отправляет само приложение:
[`DUML_COMMAND_AUDIT.md`](DUML_COMMAND_AUDIT.md).

## Как читать таблицы

| Метка | Значение |
|---|---|
| `SEND` | В бинарнике найдено формирование/отправка фиксированной пары |
| `QUERY` | Синхронный запрос с ожиданием результата |
| `PUSH` | Отправка состояния/события без доказанного request/response contract |
| `HANDLER` | Входной обработчик или регистрация команды |
| `EXTENDED` | Внутренний DUSS identifier шире обычной пары из двух однобайтовых полей |

Имя функции надёжно показывает контекст, но не всегда полностью раскрывает
payload. Поэтому в колонке «уровень» отдельно отмечены точные и контекстные
выводы.

## Исходный корпус

Сохранённый рабочий corpus:
`fpga_tang_nano_9k_card_reader-spinal/.scratch/rc_rm510_20260723/`.

| Файл | Размер | Build ID | SHA-256 |
|---|---:|---|---|
| `dji_wlm` | 507296 | `e14a06545de716c6332364c4c46cfa21` | `f505f027b09e5fee7eaca0f6089c41a866d630e5e0fe70087ba875543f8dd013` |
| `dji_sdrs_agent` | 252472 | `dc7b9ab48d0e18d593c63ad78a65be1e` | `b1092ec65d76672182cc0c0c1d125be58bf8ab447ad3e3fbfab9eb37019d990e` |
| `dji_link` | 264080 в исходной копии | `370079f72741e7dee0216f75333e0b86` | `60478c1f366675e5baf9194cd5b44ebe26ef2d23ae8c662a773aa29be5ddab31` |
| `libduml_frwk.so` | 1494128 | `b70586902d0f6e7f8f7926af5d89b391` | `0a8ec725aa6b72d4cc087b3b95120928138a25f6694c98d9a441bfd071ad34ce` |
| `libwlm.so` | 294952 | `2d6d64b85a03802c6e10a0b1016d1d69` | `2b63101f2b01aceab6de6e63afc7dd8694710d4ab8e6e154adfb023b73337931` |
| `dji_mb_ctrl` | 20024 | `698984164eff96d0f90a4e415186fd9f` | `d0ae0666c9937afe3ed604576ca7d92b661c7a56b9c6a838572c469326175cb2` |

Для `dji_wlm`, `dji_sdrs_agent` и `dji_link` использована встроенная
`.gnu_debugdata`: символы позволяют привязать константу команды к конкретной
функции. `libduml_frwk.so`, `libwlm.so` и `dji_mb_ctrl` важны как transport/
framework evidence, но в этом проходе новых фиксированных product-команд из
них не извлечено.

Во время извлечения `.gnu_debugdata` `llvm-objcopy` нормализовал рабочую
scratch-копию `dji_link`: текущий контейнер имеет размер 264072 и SHA-256
`cd02dd84f16b4ad1d18c96fefa203740aa07290a547ec429f04ba98e6e77122c`.
Build ID и кодовые адреса не изменились; в таблице сохранены размер и hash
исходной полученной копии. Рядом оставлены производные
`dji_link.gnu_debugdata.xz` и `dji_link.gnu_debugdata.elf`.

## `dji_wlm`

| Команда | Тип | Функция | Call site | Что делает | Уровень |
|---|---|---|---:|---|---|
| `08:65` | `QUERY` | `wlm_bw_bybrid_task` | `0x35fd8` | Синхронный запрос внутри задачи hybrid bandwidth; точный payload contract не декодирован | `INFERRED` |
| `07:A120` | `EXTENDED/PUSH` | `event_track_send` | `0x59170`, `0x59460` | Event tracking/report. `0xA120` — расширенный ID, а не legacy byte `0x20` | `INFERRED` |
| `51:04` | `PUSH` | `wlm_push_dev_osd` | `0x5cc1c` | Device OSD/status push | `CONFIRMED` по symbol context |
| `51:05` | `PUSH` | `wlm_push_link_sw_result2sysmode`; `wlm_inform_route_sw` | `0x5e2c0`, `0x67ec8` | Результат link switch и уведомление route switch | `CONFIRMED` по symbol context |
| `51:07` | `QUERY` | `__wlm_link_ctl` | `0x670e8` | Link control request; payload ещё не декодирован | `INFERRED` |
| `51:0C` | `QUERY` | `wlm_link_state_manage_task` | `0x5b6dc` | Одна из двух команд синхронизации/управления link state | `INFERRED` |
| `51:14` | `QUERY` | `wlm_link_state_manage_task` | `0x5b910` | Вторая команда link-state management; точное отличие от `51:0C` требует разбора payload | `INFERRED` |
| `09:21` | `QUERY` | `wlm_lk_ctrl_set_sdr_param` | `0x645bc`, `0x64620`, `0x64684` | Получение текущего SDR/link состояния; до трёх попыток | `CONFIRMED` для control flow |
| `09:EC` | `SEND` | `wlm_lk_ctrl_set_sdr_param` | `0x64900`, `0x64974`, `0x649e8` | Wi-Fi/SDR coexistence: `00 03` silence SDR 2.4G, `00 04` silence SDR 5.8G, `00 00` reset/ordinary branch; до трёх попыток при ошибке | `CONFIRMED` |
| `18:35` | `SEND` | `wlm_select_upgrade_case`; `wlm_capture_dongle_log` | `0x71334`, `0x71564` | Один multiplexed diagnostic/control ID к host `0x0e06`; upgrade-case и capture dongle log различаются payload/action | `CONFIRMED` по двум функциям, payload layout частичный |

`09:EC` вызывается событийно при переключении coexistence/частот. Жёсткого
цикла «каждые 10 секунд» в этой функции не найдено.

`wlm_forward_msg_send` — общий forward path: вызывающий передаёт ID во время
выполнения. Такие команды нельзя честно добавить в таблицу как фиксированные
пары только по этому wrapper.

## `dji_link`

| Команда | Тип | Функция | Call site | Что делает | Уровень |
|---|---|---|---:|---|---|
| `07:A120` | `EXTENDED/QUERY` | `dji_init_task_entry` | `0x134b8` | Внутренняя синхронизация/event-track при запуске сервиса | `INFERRED` |
| `08:32` | `PUSH` | `dji_command_live_view_push_to_rc` | `0x1c270` | Live-view state push на RC | `CONFIRMED` по symbol context |
| `00:32` | `PUSH` | `dji_command_active_push` | `0x1c3b8` | Activation state push | `CONFIRMED` по symbol context |
| `00:32` | `PUSH` | `dji_command_active_auth_push` | `0x1c514` | Activation authorization push; тот же ID, другой payload path | `CONFIRMED` по symbol context |
| `06:C4` | `SEND` | `dji_command_deactive_wipe_data` | `0x1c6a8` | Deactivation/data-wipe command | `CONFIRMED` по symbol context |
| `06:A5` | `QUERY` | `dji_command_set_mcu_active` | `0x1c7d4` | Установка MCU active state/flag | `CONFIRMED` по symbol context |
| `18:39` | `SEND` | `send_fusion_info_to_lte` | `0x1cb8c` | Fusion info в LTE service, destination `0x0e06` | `CONFIRMED` |
| `51:10` | `SEND` | `send_lte_info_to_wlm` | `0x1ccb4` | LTE info в WLM, destination `0x0e07` | `CONFIRMED` |
| `0200:0D05` | `EXTENDED/QUERY` | `secure_open_debug_auth` | `0x1e844` | Внутренняя secure debug authorization | `CONFIRMED` по symbol context; wire layout расширенный |

`dlink_forward_message_with_no_ack` и `duss_send_pack` также принимают
команду во время выполнения. Наличие вызова wrapper не раскрывает полный набор
пересылаемых ID.

## `dji_sdrs_agent`

| Команда | Тип | Функция | Call site | Что делает | Уровень |
|---|---|---|---:|---|---|
| `07:A120` | `EXTENDED/QUERY` | `sa_relay_task_entry` | `0x1f574` | Общий relay task operation | `INFERRED` |
| `07:A120` | `EXTENDED/QUERY` | `sa_relay_get_profile` | `0x1fcb0` | Получение relay profile | `CONFIRMED` по symbol context |
| `07:A120` | `EXTENDED/QUERY` | `sa_relay_route_switch` | `0x1fe94` | Relay route switch | `CONFIRMED` по symbol context |
| `07:A120` | `EXTENDED/QUERY` | `sa_relay_shutdown_pigeon` | `0x2001c` | Shutdown relay/pigeon path | `CONFIRMED` по symbol context |

Разные операции multiplexed через один расширенный ID и различаются payload.
`sa_heartbeat_task` отправляет команду, полученную в runtime (`w21`), на
destination `0x20:0e00`; фиксировать для неё выдуманную пару нельзя.

В ELF есть именованные request handlers, но их числовая пара пока не
восстановлена статически:

| Handler | Контекст |
|---|---|
| `sa_event_ping` | Ping request |
| `sa_event_sysreboot` | System reboot request |
| `sa_event_amt_nvram_rw` | AMT NVRAM read/write |
| `sa_event_common_query_device_info` | Device-info query |
| `sa_event_rt_control_by_name` | Runtime control by name |

Регистрация проходит через `duss_register_cmd`/
`duss_event_register_dynamic_command` с таблицами, формируемыми в runtime.
Названия handler сами по себе не дают права назначить им случайные ID.

## Cross-check с live-потоком RC2

Ниже перечислены пары, реально наблюдавшиеся приложением. Это transport
evidence, а не доказательство наличия handler именно в трёх ELF выше.

| Команда | Наблюдение |
|---|---|
| `03:43` | GPS/flight telemetry, поля спутников и GPS state |
| `03:44` | Home Point telemetry; на проверенном layout `home_state` at offset 20 |
| `06:77` | Один passive RC status frame, payload `00`; точная семантика неизвестна |
| `06:A4` | RC telemetry/status, payload `00` |
| `06:AE` | Основной background RC payload на `40009`; семантика неизвестна |
| `00:81`, `00:82` | Controller identity telemetry |
| `07:18`, `07:30` | Country/area write family |
| `07:19` | Country-code query family |
| `51:04` | Cellular/device OSD telemetry; совпадает со статическим `wlm_push_dev_osd` |
| `51:14` | Cellular/link telemetry с полной aircraft identity; совпадает с командой link-state task, но layout по одной функции ещё не назван окончательно |

Полная частотная карта live capture сохранена в
[`DUML_STREAM_MAP.md`](DUML_STREAM_MAP.md).

## Граница полноты

Таблица включает все фиксированные пары, которые в текущем проходе удалось
привязать к именованным функциям `dji_wlm`, `dji_link` и `dji_sdrs_agent`.
Она заведомо не включает:

- ID, передаваемые в generic forward/send wrappers только во время выполнения;
- команды remote MCU, aircraft, camera и flight controller, которых нет в
  userspace ELF пульта;
- handler tables, числовые поля которых ещё требуют data-flow reconstruction;
- зашифрованные/упакованные компоненты, не входившие в сохранённый corpus.

При следующем расширении справочника надо добавлять не просто найденный
hex-number, а минимум: файл и Build ID, функцию, call site/registration site,
направление, payload evidence и уровень уверенности.
