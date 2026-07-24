# DJI LTE: DUML/DUSS-команды, активация и периодические задачи

Дата фиксации: 2026-07-24.

Этот справочник отделяет четыре механизма, которые раньше легко было смешать:

1. DUML command set `0x18` — управление и telemetry службы `dji_lte`;
2. command set `0x51` — взаимодействие LTE с WLM и radio management;
3. активацию самого LTE-dongle и проверку её состояния через `00:32`;
4. AT-команды модема, dial/redial и настройку разрешённых LTE bands.

Главный вывод: одной универсальной команды «включить 4G» в проверенном коде
нет. Штатный путь состоит из обнаружения и активации dongle, готовности SIM,
dial, обнаружения peer dongle, pairing, WLM ability negotiation и выбора
link/service mode. Периодические отчёты состояния обслуживают этот автомат, но
не являются FCC keepalive.

## Корпус и уровень доказательств

| Платформа | ELF | Build ID | SHA-256 |
|---|---|---|---|
| WA530 | `dji_lte` | `d3568587bc0aea999958bd64db633f21` | `deecd266cdff0a118988cb78b4d425113402381756219651eff4ea457b0a2cef` |
| RC Pro 2 build 576 | `dji_lte` | `2ae6c041582be14d56507a2b70910792` | `6788276b77649e6717227ba6d70792d97fdcf135999b425b56af43db1546e8ec` |
| RC Pro 2 build 139 | `dji_lte` | `89ef10b5be42571ec1a07572f046d6ec` | `27052d52c3727b255ee6edade2e108230bebe1df1c3a1d178c9e061ed20d88d5` |

Обозначения:

- `CONFIRMED` — handler, sender или state transition восстановлен из кода;
- `OBSERVED` — кадр действительно присутствовал в ранее снятом live stream;
- `INFERRED` — семейство операции подтверждено именем и контекстом, но полный
  payload layout не восстановлен;
- `UNKNOWN` — известны только route/ID либо часть полей.

Ни одна команда в рамках этого анализа на реальное устройство не
отправлялась.

## Входящая таблица command set `0x18`

В RC Pro 2 build 576 таблица имеет `count=0x4F`, то есть slots
`0x00..0x4E`. В WA530 она расширена до `count=0x57`, slots `0x00..0x56`.
Общие handlers `0x01..0x4E` совпадают. Пустой slot означает отсутствие
зарегистрированного request/ACK handler в этом процессе, а не глобальное
отсутствие команды в других устройствах DJI.

| ID | Request handler | ACK handler | Установленный смысл | Уровень |
|---:|---|---|---|---|
| `01` | `lte_ability_negotiate_req` | — | Базовая LTE ability negotiation | `CONFIRMED` |
| `14` | `lte_event_cb_lte_get_state` | — | Запрос состояния LTE-службы | `CONFIRMED` |
| `15` | `set_dongle_apn_info` | — | Запись APN dongle | `CONFIRMED` |
| `24` | `lte_event_special_remote_control` | — | Специальное remote control событие; layout не закрыт | `INFERRED` |
| `2F` | `lte_web_factory_check_status` | — | Factory/web check status | `INFERRED` |
| `31` | `sdr_pair_done_handler` | — | Завершение SDR pairing | `CONFIRMED` |
| `33` | `lte_change_netmode` | — | Смена network mode | `CONFIRMED` |
| `35` | `lte_ci_test_process` | — | CI/test multiplexor | `CONFIRMED` |
| `36` | `get_peer_ipv6` | `get_peer_ipv6_ack` | IPv6 peer | `CONFIRMED` |
| `37` | `get_peer_lte_state_info` | — | LTE state peer | `CONFIRMED` |
| `38` | `get_peer_lte_link_info` | — | Link state peer | `CONFIRMED` |
| `3C` | `get_peer_service_info` | `get_peer_service_info_ack` | Service info peer | `CONFIRMED` |
| `3D` | `get_dongle_upgrade_info` | — | Состояние/информация upgrade dongle | `CONFIRMED` |
| `3E` | `lte_get_tri_sim_mode` | — | Чтение tri-SIM mode | `CONFIRMED` |
| `3F` | `lte_set_tri_sim_mode` | — | Запись tri-SIM mode | `CONFIRMED` |
| `43` | `common_get_dongle_release_note` | — | Release note dongle | `CONFIRMED` |
| `45` | `get_dongle_subscribe_info` | — | Subscription state/info | `CONFIRMED` |
| `46` | `get_uav_lte_connect_fail_reason` | — | Причина отказа LTE на UAV | `CONFIRMED` |
| `48` | `lte_privatization_req_handle` | `lte_privatization_ack_handle` | Privatization/private deployment | `CONFIRMED` |
| `49` | `lte_get_test_tool_cmd` | — | Test-tool command | `CONFIRMED` |
| `4B` | `lte_set_esim_req_handle` | — | eSIM request/configuration | `CONFIRMED` |
| `4C` | — | `lte_esim_init_ack_handle` | ACK инициализации eSIM | `CONFIRMED` |
| `4D` | `lte_trisim_req_handle` | `lte_trisim_ack_handle` | Tri-SIM exchange | `CONFIRMED` |
| `4E` | `lte_link_diag_req_handle` | — | Link diagnostics | `CONFIRMED` |
| `51` | `lte_sub2g_info_req` | — | Sub-2G information/control; только WA530 | `CONFIRMED` |
| `52` | `lte_pair_method_req` | — | Запрос/выбор способа LTE pairing; только WA530 | `CONFIRMED` |
| `53` | `get_peer_bind_info_byserver` | — | Bind info через server; только WA530 | `CONFIRMED` |
| `54` | `get_byserver_service_info_req` | `get_byserver_service_info_ack` | Service info для pairing через server; только WA530 | `CONFIRMED` |
| `56` | `lte_link_ctrl_req` | — | Управление LTE link; только WA530 | `CONFIRMED` |

Регистрация WA530 находится в `lte_event_uav_start` по адресу `0x13c450`.
Таблица `0x18` начинается по VA `0x242d80`, состоит из `0x57` slots по
24 bytes. В RC Pro 2 build 576 та же таблица начинается по VA `0x1851f8`,
имеет `0x4F` slots и совпадающие handlers до `0x4E`.

### Исходящие `18:xx`

Ниже перечислены ID, для которых непосредственно найдено построение
исходящего event header. Одинаковый ID может быть request, response или push в
зависимости от sender и направления.

| ID | Sender/function | Смысл | Уровень |
|---:|---|---|---|
| `01` | `lte_duss_event_oob_write` | OOB/ability exchange | `INFERRED` |
| `31` | `lte_change_netmode` | Результат/синхронизация net mode и SDR pair | `INFERRED` |
| `36` | `push_ipv6` | IPv6 info | `CONFIRMED` |
| `37` | `send_lte_state_to_other_service`, `push_dongle_state` | LTE/dongle state | `CONFIRMED` |
| `38` | `push_link_state` | Link state | `CONFIRMED` |
| `3B` | `send_capture_data` | Diagnostic capture | `CONFIRMED` |
| `3C` | `push_service_info` | Service info | `CONFIRMED` |
| `40` | `push_dongle_info_to_app` | Dongle info в app | `CONFIRMED`, `OBSERVED` |
| `42` | `send_location_info_to_fc` | LTE location info в flight controller | `CONFIRMED` |
| `44` | `send_upgrade_cmd_to_app` | Upgrade event в app | `CONFIRMED` |
| `46` | `push_debug_info_to_app` | Debug/fail-reason info в app | `CONFIRMED` |
| `47` | `uav_send_1847_to_rmc` | UAV report в RMC | `INFERRED` |
| `4A` | `push_dongle_sim_info_to_app`, `lte_resp_dongle_sim_info` | SIM info | `CONFIRMED` |
| `4B` | `lte_dock_dongle_workaround_handle` | eSIM/dongle request path | `CONFIRMED` |
| `4C` | `esim_init_task_entry` | eSIM initialization/ACK path | `CONFIRMED` |
| `4F` | `push_dongle_nc_info_to_app` | Network/carrier info в app | `INFERRED` |
| `50` | `send_multi_dongle_info` | Multi-dongle TLV info в app | `CONFIRMED`, `OBSERVED` |
| `51` | `lte_cloud_control_handler` | Cloud/sub-2G control exchange | `INFERRED` |
| `52` | `pair_event_handle`, `req_lte_pair_method` | Pairing method | `CONFIRMED` |
| `54` | `push_service_info` | Service info для by-server pairing | `CONFIRMED` |
| `55` | `lte_network_flow_control` | LTE flow control | `CONFIRMED` |
| `57` | `push_device_info` | Device, product SN и modem inventory; RC Pro 2 build 576 | `CONFIRMED` |

Live stream ранее независимо подтвердил:

- `18:40` с payload `01 00` от LTE service к app;
- `18:50` с multi-dongle TLV payload.

RC Pro 2 build 576 отправляет `18:57`, хотя его собственная входящая таблица
заканчивается на `0x4E`. Это нормальная асимметрия: отправителю не нужен
локальный handler собственной push-команды.

## Активация — не одна операция

### 1. Dongle activation и `00:32`

Command set `0x00`, ID `0x32` обслуживается функцией
`common_dongle_activate` (`0x1429b0` в WA530). Она разбирает activation action
и запрос состояния, привязанные к SN dongle.

Периодическая задача `device_active` раз в 3000 ms вызывает
`lte_query_device_active_state` (`0x14fa50`), которая отправляет `00:32` с
однобайтовым payload `0x31`. ACK обрабатывает
`lte_query_device_active_state_ack` (`0x14fbc0`) и обновляет локальный
activation state.

Это не SIM attach и не команда выбора LTE link. Строки и ветки кода прямо
показывают:

- activation/deactivation хранит status и дату/время активации;
- поддерживается проверка expiration;
- при `dial_check_device_active=true` неактивный dongle блокирует dial:
  `device is not activated, dial not allowed`.

### 2. AT activation record

Для нескольких семейств модемов зарегистрирована одна схема:

```text
AT+GTACTINFO="00"             deactivate
AT+GTACTINFO="01"             activate
AT+GTACTINFO?                 query status
AT+GTACTINFO="%012d"          запись activation timestamp/context
```

В WA530 найдены три `need_send_activate` для разных modem backends:
`0x10bf40`, `0x1172c0`, `0x123c40`. Проверенная первая реализация возвращает
true только при подходящем внутреннем state и установленном activation flag;
она не посылает команду безусловно на каждом scheduler tick.

В data descriptors проверка этой операции обслуживается с шагом 500 или
1000 ms в зависимости от backend. Это частота проверки state machine, а не
доказательство постоянной отправки `AT+GTACTINFO`.

### 3. Dial, pairing и WLM

После activation остаются отдельные стадии:

1. готовность SIM/eSIM и APN;
2. modem dial и IP/network state;
3. наличие peer dongle;
4. LTE pairing — обычно через SDR;
5. WLM ability negotiation;
6. выбор допустимого service/link mode.

`lte_query_wlm_nego_result` (`0x17fe40`) формирует `51:42` и регистрируется
как callback внутри `lte_pair_init`. Команды WLM:

| Команда | Назначение |
|---|---|
| `51:41` | Ability negotiation request/ACK |
| `51:42` | Ability negotiation result request/ACK |
| `18:52` | Запрос/выбор pairing method |
| `18:54` | Service-info exchange для pairing через server |

Задача `pair_event` запускается раз в 2000 ms, но выполняет условный
`pair_event_handle`: наличие scheduler period не означает повторную попытку
pairing или DUML send каждые две секунды во всех состояниях.

## Band control

`lte_config_parse_band_ctrl` (`0x191460`) и
`lte_config_parse_band_ctrl_v2` (`0x1909b0`) читают country/PLMN policy и
списки запрещённых bands. Для одного modem backend
`need_send_band_ctrl` (`0x123ec0`) строит:

```text
AT*BAND=5,78,262331,%u,%u,0,2,2,0
```

Команда формируется только если:

- включена соответствующая config/state ветка;
- получены текущие и требуемые bands;
- текущая конфигурация отличается от policy;
- не активны конфликтующие modem/eSIM состояния.

Соседний автомат имеет timeout около 20 секунд для состояний выполнения
band-control операции. Это timeout зависшей операции, а не периодическая
отправка band command каждые 20 секунд.

Строки кода подтверждают обе ветки изменения: запрещённые bands закрываются,
а ранее закрытый band может быть снова открыт при смене policy. Поэтому band
control — country/PLMN policy, а не общий «ускоритель 4G».

## Redial и reset

### Подтверждённые причины redial

Вызов `lte_dev_redial` идёт из dial state machine, а конкретная реализация
выбирается через modem vtable. Подтверждены следующие event-driven причины:

| Причина | Код/строка |
|---|---|
| Смена network-device state | `net_dev_status_parser`: `net state change redial` |
| Изменение IP/DNS | `dns_parser`: `ip change need redial` |
| Изменение ECM IP | ECM parser: `ip change need redial` |
| Переключение SIM slot | `need_send_slot_switch`: `slot_sw: redial` |
| Завершение eSIM switch | ветка `lte_dev_task_dial` после результата switch |
| Ошибка full upgrade dongle | `dji_set_dongle_full_upgrade_state` и mini-вариант |

Это реакции на изменение состояния. Фиксированного «redial каждые N секунд»
в этих ветках нет.

### Подтверждённые причины reset

Найдены отдельные reset-сценарии:

- переключение SIM card;
- изменение SIM status;
- невозможность прочитать firmware version dongle — USB reset;
- modem exception — `lte_modem_exception_reset_modem_handle` или USB reset;
- soft reset при включении production mode;
- recovery/upgrade slot;
- явный upgrade-state reset.

`need_reset_modem` (`0x10cc40`) для literal `AT+RESET` требует внутренний flag,
читает Android property `lte.upgrading_netcard` и сравнивает её со своим
dongle identifier. Следовательно, этот `AT+RESET` относится к upgrade текущей
network card, а не к общему keepalive.

## Периодические задачи `dji_lte`

Ниже указаны scheduler periods из WA530. RC Pro 2 build 576 содержит тот же
основной набор, а также отдельную задачу `device_info`.

| Period | Задачи/callbacks | Реальный смысл |
|---:|---|---|
| `500 ms` | `devlist`, `activelist`, `ipv6_info`, `service_info`, `dongle_state`, `link_state`, `cache_info`, `country_code`, `privatization`, `sub_state`, `liblte_modem_info`, `share_info`, `state2other`, `traffic_ctrl`, `debug_info`, `ssfn`, `local_link_state` | Быстрые проверки и условные state/report updates |
| `1000 ms` | `dongle_nc_info`, `led_ctrl`, `flow_ctrl` | Network info, LED и flow-control state |
| `1000 ms` | `device_info` → `18:57`, только RC Pro 2 build 576 | Ground device/modem inventory push |
| `2000 ms` | `pair_event` | Условный pairing state machine |
| `2000 ms` | `rm_ctrl` → `51:1E` на WA530 | Local frequency information для radio management |
| `3000 ms` | `factory_mode`, `device_active` | Запрос factory/activation state |
| `5000 ms` | `location` | LTE location update |
| `20000 ms` | `data_capture` | Diagnostic capture info |

Критическая оговорка: `lte_cycle_mission_submit` вызывает callback по
расписанию, но большинство callbacks сначала сравнивает flags, cached state,
роль устройства, наличие modem/peer и изменения данных. Таблица не
эквивалентна таблице безусловных DUML sends.

Особенно важно:

- периодический `51:1E` — local frequency report для WLM radio management;
- периодический `18:57` — device/modem inventory;
- `device_active` — проверка activation state;
- ни один из них не является FCC keepalive или повторной записью FCC profile.

Добавлять в FreeFCC опрос или повторную отправку этих команд каждые 5 секунд
не требуется: штатный native `dji_lte` уже содержит собственные state machines
и существенно более частые условные проверки.

## Практический итог для дальнейших экспериментов

1. Не использовать sweep command IDs как способ LTE activation: разные IDs
   имеют APN, eSIM, diagnostics, bind, link-control и test contracts.
2. Не переносить payload между `0x18`, `0x51` и `0x00`, даже если смысл названия
   похож.
3. Не считать успешный write в `/duss/mb/0x205` доказательством activation,
   pairing или LTE link.
4. Для пассивной диагностики полезнее наблюдать transitions `00:32`,
   `18:37/38/40/50/57`, `51:41/42` и WLM link state, чем отправлять их заново.
5. `09:EC` остаётся отдельной Wi-Fi/SDR coexistence-настройкой и в этот
   activation/redial автомат не входит.

## Оставшиеся пробелы

| Вопрос | Статус |
|---|---|
| Полный byte layout `18:24`, `18:35`, `18:47`, `18:4F`, `18:51` | Частичный; handlers/senders известны |
| Точные destination host и role для каждого исходящего `18:xx` на всех продуктах | Зависят от `lte_cfg.json` и runtime topology |
| Все условия раннего выхода каждого periodic callback | Не сведены в одну state matrix |
| End-to-end переход activation → paired LTE на реальном dual-dongle комплекте | Требует отдельной физической проверки |
| Физический эффект ручной отправки отдельных control payload | Намеренно не проверялся |

Эти пробелы не мешают основному выводу: activation, dial, pairing, WLM
negotiation и link selection — разные стадии, а найденные короткие периоды
относятся к условным внутренним state machines, а не к необходимости внешнего
keepalive.
