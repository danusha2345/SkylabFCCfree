# Сторонние DJI FCC/4G-приложения и механизмы

Дата фиксации: 2026-07-25.

Цель документа — собрать найденные сторонние приложения и открытые реализации,
которые включают FCC либо заявляют разблокировку DJI Enhanced Transmission/4G,
и отделить реальные технические признаки от рекламы и неподтверждённых
назначений DUML-команд.

Связанные внутренние документы:

- [Live-карта DJI cellular-модемов](DJI_CELLULAR_MODEM_LIVE_MAP.md);
- [справочник DJI LTE DUML/DUSS](LTE_DUML_COMMAND_REFERENCE.md);
- [анализ Drone-Hacks Mobile APK](DRONE_HACKS_MOBILE_APK_ANALYSIS.md);
- [аудит команд FreeFCC](DUML_COMMAND_AUDIT.md);
- [разбор command set `0x51`](WLM_CMDSET_51.md).

## Уровни доказательств

- `OBSERVED` — непосредственно найдено в APK, ELF, исходном коде, документации
  производителя или декомпилированном call path.
- `DERIVED` — следует из нескольких наблюдений, но не является отдельным
  аппаратным тестом.
- `HYPOTHESIS` — правдоподобное объяснение, для которого ещё нужен capture или
  реальное устройство.
- `NEGATIVE` — искомая команда или механизм не найден в проверенном scope.
- `VENDOR CLAIM` — утверждение разработчика продукта, не подтверждённое
  независимым capture.

Ни один сторонний APK из этого исследования не устанавливался на пульт.
Сторонние команды на реальное устройство не отправлялись, OpenFCC OTA
исследовалась только офлайн и не применялась.

## Краткий результат

| Решение | FCC | 4G | Установленный механизм | Статус |
|---|---:|---:|---|---|
| NLD FCC Android | Да | Заявлено | USB/AOA/RCLink, серверный профиль, native DUML, фоновое поддержание FCC | APK подтверждает механизм; plaintext профиля скрыт |
| OpenFCC | Да | Да | Native relay к DUSS, защищённые команды, aircraft SN, Always-on 4G; опциональный controller OTA flow | Сильное статическое evidence; точные 4G-кадры скрыты |
| Drone Tweaks | Да | Отдельно не заявлено | Модифицированный DJI Fly с изменённой региональной логикой | Механизм FCC подтверждён самим поставщиком |
| Drone-Hacks | Да | Не включает | Патч firmware либо серверная One-Shot-команда | Точные mobile payload отсутствуют в APK |
| FCC Switch / открытые N1/N2 apps | Да | Нет | Два открытых DUML-кадра через USB serial | Публичный минимальный FCC-примитив |
| FreeFCC-USB | Заявлено | Нет | 21-кадровый профиль через AOA/RCLink | Исходники открыты, но README отмечает отсутствие hardware test |
| O3/FPV `ham_cfg_support` | Да | Нет | Файл-флаг на SD-карте очков | Другой product family, не consumer LTE |
| DJI Mobile SDK | Не предоставляет FCC unlock | Да | LTE authentication, policy check, WLM link/service mode | Штатный многостадийный путь |

## Проверенный APK-корпус

| Артефакт | Версия | Размер | SHA-256 |
|---|---|---:|---|
| NLD FCC `com.nolimitdronez.nldfcc` | `1.2.0.5` (`41`) | 4,894,384 | `a37d0125334c79038d8dd63d4a12e4da29be884ae5510853de94211d497cb580` |
| NLD FCC standalone `com.nolimitdronez.nldfcc` | `2.0.0.6` (`46`) | 7,269,392 | `4a6e07a2ee1fbd7f2dacd4bb500ed3a2747490be1d8adb4d6f212d99a4a15126` |
| NLD FCC из `NLDFCC_Smart_RC.zip` | `2.0.0.6` (`46`) | 7,278,464 | `1035f0aa22e158fd1703e14dd3bd2198845da4c2113454f9ac3a4569c41ee474` |
| OpenFCC `app.openfcc` | `1.2.30` (`35`) | 12,000,582 | `04a580af86b780d7d497a676738e16ee8d609983a4c5b60dc7c772d23f87e8ee` |
| OpenFCC Windows Setup | `1.2.4` family | 54,057,943 | `50bc1e3a1ac532af4ed54a0057960610e619ddfe0246233f134a5dd02d26b741` |
| Drone-Hacks Mobile `com.dronehacks.client` | `1.0.1` (`1000001`) | 89,211,250 | `4694dd10fb78d1c317906b4bac15aa8e6aaa9096a941bd7f93388068c6a955dd` |

Примечание: APK и декомпиляции хранятся только в ignored `.scratch/` либо в
локальном `Downloads`; исходные бинарники не добавляются в Git.

## NLD FCC Android

Источники:

- [страница продукта](https://nolimitdronez.com/nldfcc-for-android);
- [страница загрузки](https://nolimitdronez.com/download);
- [анонс NLD FCC Android](https://nolimitdronez.com/introducing-nld-fcc-for-android).

### Что подтверждает APK

`OBSERVED`:

- приложение имеет собственный разбор/сборку DUML;
- поддержаны Android Open Accessory, USB bulk/VCOM и внутренний `RCLink` для
  smart controller;
- для разных путей выбираются разные component routes;
- native-библиотека `libnld-core.so` скрывает построение профиля, API URL,
  шифрование/дешифрование ответов и обработку DUML response;
- запрос профиля содержит `DEVICE_TYPE` и разновидность transport/license;
- foreground service показывает состояние `Maintaining FCC mode`;
- для smart-controller режима работает coroutine-loop с паузой `2000 ms`;
- на каждом проходе вызывается применение/проверка профиля; native cache
  проверяется первым, а повторная сеть нужна только при специальном результате,
  поэтому это не означает HTTP-запрос каждые две секунды;
- уведомление содержит действие остановки FCC;
- UI отображает этапы `Enable 4G SIM`, `Enable FCC Mode` и
  `Disable Remote ID`;
- в `2.0.0.6` все семь `assets/profiles/*.json` побайтово совпадают с blob из
  истории FreeFCC: `fcc.json` совпадает с текущим полным 21-frame профилем,
  а `4g.json` — с legacy-версией FreeFCC commit `0ad9037`;
- Java-код `2.0.0.6` не открывает эти profile assets: активный FCC/4G payload
  по-прежнему приходит через server/native path и применяется `NativeCore.z1d`;
- два APK `2.0.0.6` имеют одинаковые profile assets, `libnld-core.so` и signing
  certificate, но разные manifests: ZIP-билд содержит `BootCompletedReceiver`,
  а standalone-билд его не содержит, хотя UI Auto Start сохранён;
- `packageinstaller.apk` из NLD ZIP побайтово равен локальному
  `01_PackageInstaller.apk`, SHA-256
  `523361acbe62587fa61e00a92369e87daa0d812232b8942deba67771ccf2633a`.

`DERIVED`: NLD — не простая оболочка над двумя известными N1/N2-кадрами.
Приложение имеет полноценный транспорт, model-dependent профиль, обработку
ответов и фоновое поддержание состояния.

`UNKNOWN`: UI из трёх шагов не доказывает три отдельные DUML-команды. Все три
операции могут входить в один серверный профиль. Plaintext команд в DEX,
активном call path и обычных строках `libnld-core.so` не найден. Наличие
открытых JSON в `2.0.0.6` этого не меняет: у них нет Java call site.

`VENDOR CLAIM`: NLD заявляет worldwide 4G SIM и отключение Remote ID на
поддерживаемых моделях. Без plaintext capture нельзя установить, какой кадр
отвечает за 4G и одинаков ли он для разных aircraft.

### Важное сравнение с FreeFCC

NLD поддерживает FCC примерно раз в две секунды, но точное число реально
отправленных кадров скрыто за native result/cache. Это не является основанием
ускорять 10-секундный FreeFCC keepalive или возвращать legacy 128-frame sweep:
NLD может проверять native state и отправлять только server-selected часть.

## OpenFCC

Источники:

- [главная страница OpenFCC](https://openfcc.app/);
- [совместимость](https://openfcc.app/compatibility);
- [patch notes](https://openfcc.app/patch-notes).

### APK

`OBSERVED` в `libopenfcc_secure.so`:

- endpoint `/duss/mb/0x205`;
- отдельные native relay sessions;
- JNI entry points `nativeOpenRelaySession`, `nativeRelaySessionSend`,
  `nativeDecryptEnvelope`, `nativeExportRelayPlaintextOnce` и
  `nativeBuildDumlFrame`;
- отдельные HKDF contexts для FCC и 4G sessions;
- foreground path `FourGHeartbeatService`;
- режим `always_on_4g`;
- получение и отображение aircraft serial;
- encrypted/enveloped command material вместо открытого JSON-профиля.

APK содержит правдоподобно названные decoy-классы и документы. Названия вроде
`FccSequenceWm261Legacy` нельзя считать источником истины без перехода к
native relay и реальному plaintext.

`DERIVED`: OpenFCC действительно имеет отдельную долговременную 4G-сессию, а
не только маркетинговую надпись. Aircraft serial участвует в 4G path, что
согласуется с patch notes: разработчик называет SN обязательным для
диагностики 4G.

`UNKNOWN`: точные DUML `cmdSet`, `cmdId`, route и payload защищены. Статический
APK не позволяет доказать, какие команды включают WLM, какие снимают country
gate и какие только поддерживают уже созданную сессию.

### Desktop launcher и controller firmware

`OBSERVED` в декомпилированном launcher:

- предусмотрен отдельный `4G controller OTA swap`;
- launcher получает одноразовый download grant;
- проверяет SHA-256 загруженного `update.zip`;
- использует Android `update_engine_client` для A/B OTA;
- после успешного применения перезагружает контроллер;
- внутреннее описание связывает swap с открытием controller WLM gate, а
  активационные кадры затем отправляет OpenFCC APK.

`OBSERVED`: использованный launcher artifact и загруженная им OpenFCC
`firmware_update.zip` исследованы отдельно. Это полная DJI-signed A/B OTA для
`rc520`, build `V55.31.01.39/139`, SHA-256
`182e459ba29fc00aec9c66d547cd3fe4fd14bfa47d7063e486af7b82ba3542f6`, а не
небольшой патч одной функции. Проверены Android A/B signatures и сравнение
partition images; OTA на пульт не применялась. Подробности и границы выводов
см. в [live-карте DJI cellular-модемов](DJI_CELLULAR_MODEM_LIVE_MAP.md).

`DERIVED`: OpenFCC разделяет как минимум два слоя:

1. firmware/capability gate на контроллере;
2. последующую FCC/4G-командную сессию приложения.

Это лучше объясняет рабочий 4G на регионально заблокированных системах, чем
безусловная отправка одного общего набора кадров.

## Drone Tweaks

Источники:

- [Drone Tweaks FAQ](https://www.drone-tweaks.com/faq);
- [линейка продуктов](https://www.drone-tweaks.com/).

`OBSERVED` по документации поставщика: Drone Tweaks распространяет
модифицированную версию DJI Fly/GO4, которая сохраняет FCC mode и использует
обычный DJI account.

`DERIVED`: этот подход может изменять country/FCC checks непосредственно в
DJI Fly, то есть на другом слое, чем FreeFCC. Он не доказывает наличие особой
универсальной DUML-команды FCC.

`NEGATIVE`: на публичных страницах не найден отдельный продукт или обещание
разблокировать worldwide 4G. Отзывы о совместной работе Drone Tweaks и DJI
dongle доказывают максимум совместимость, но не включение 4G самим продуктом.

## Drone-Hacks

Источники:

- [известные проблемы Drone-Hacks](https://wiki.drone-hacks.com/en/dh2-known-issues);
- [Mavic 3 и 4G](https://wiki.drone-hacks.com/en/dh2-mavic-3-standard);
- [FCC guide](https://wiki.drone-hacks.com/en/fcc-guide).

Подробный статический разбор вынесен в
[DRONE_HACKS_MOBILE_APK_ANALYSIS.md](DRONE_HACKS_MOBILE_APK_ANALYSIS.md).

`OBSERVED`:

- Android Mobile APK подключается непосредственно к aircraft по USB;
- One-Shot FCC получает с backend готовые `cmdSet`, `cmdId` и `payload`;
- команда передаётся через общий `SendCustomPacket`;
- конкретные FCC bytes не находятся в APK;
- desktop/firmware продукт поддерживает постоянные модификации FCC отдельно от
  Mobile One-Shot.

`VENDOR CLAIM`: Drone-Hacks предупреждает, что постоянный FCC может ухудшать
работу 4G dongle, и советует `DH FCC off`.

`UNKNOWN`: формулировка «FCC signal may overpower 4G» не раскрывает физический
механизм и не подтверждена нашим RF capture. Возможны RF desense, особенности
общего radio frontend, firmware policy либо неверная интерпретация полевого
наблюдения.

Практический вывод: одновременное постоянное FCC и 4G нужно проверять отдельно,
а не считать гарантированно совместимыми или несовместимыми.

## Открытые FCC-приложения для RC-N1/N2

Проверенные исходники:

- [M4TH1EU/DJI-FCC-HACK](https://github.com/M4TH1EU/DJI-FCC-HACK);
- [posernico91-lab/dji-fcc-tool](https://github.com/posernico91-lab/dji-fcc-tool);
- [derekhe/dji-mavic-fcc](https://github.com/derekhe/dji-mavic-fcc);
- [FCC Switch в Google Play](https://play.google.com/store/apps/details?id=io.poserpy.rangeboost).

Независимые реализации отправляют одинаковые два raw frame через USB CDC
`19200 8N1`:

```text
55 0D 04 21 2A 1F 00 00 00 00 01 86 20
55 18 04 20 02 09 00 00 40 09 27 00 02 48 00 FF FF 02 00 00 00 00 81 1F
```

Второй кадр содержит:

```text
09:27 00024800ffff0200000000
```

`OBSERVED`: payload соответствует записи assistant-регистра
`0xffff0048 = 2`, ранее известной в FreeFCC как `setForceFcc`.

`DERIVED`: первый короткий frame выполняет transport/session/bootstrap роль;
называть его «сменой региона» без firmware handler evidence нельзя.

`NEGATIVE`: в этих приложениях нет включения 4G, WLM pairing, LTE
authentication или Enhanced Transmission.

## FreeFCC-USB и 21-кадровый профиль

Источник:
[doesthings/FreeFCC-USB](https://github.com/doesthings/FreeFCC-USB).

`OBSERVED` в открытом JSON:

- 21 frame, два раунда;
- встречаются `03:F9`, `06:72`, `07:30`, два `09:27`, `07:18` и другие
  команды;
- используется AOA/RCLink envelope и transport keepalive;
- описание профиля утверждает capture из `/api/trial/command`;
- README одновременно помечает приложение как не проверенное на реальном
  дроне.

`UNKNOWN`: комментарии к кадрам не являются firmware-proof. В частности,
назначения service mode, region commit и «универсальность для всех aircraft»
требуют handler analysis либо live ACK/readback.

`HYPOTHESIS`: путь `/api/trial/command` и форма model-dependent профиля похожи
на коммерческий FCC backend, но без provenance/capture нельзя приписывать
профиль конкретно NLD или OpenFCC.

`NEGATIVE`: этот 21-кадровый профиль не содержит доказанного полного DJI LTE
activation flow и не является подтверждением текущего FreeFCC `4g.json`.

## O3/FPV и `ham_cfg_support`

Источник:
[YarosMallorca/dji-o3-fcc-hack](https://github.com/YarosMallorca/dji-o3-fcc-hack).

Приложение создаёт либо удаляет файл `ham_cfg_support` на выбранной SD-карте.
Файл предназначен для DJI goggles/O3/FPV product family.

`OBSERVED`: это отдельный file-flag механизм FCC.

`NEGATIVE`: он не включает consumer DJI Cellular Dongle, не создаёт WLM pair и
не раскрывает команды Enhanced Transmission.

## Штатный DJI LTE path

Источники:

- [DJI Mobile SDK LTE](https://developer.dji.com/doc/mobile-sdk-tutorial/en/tutorials/lte.html);
- [DJI Cellular Dongle FAQ](https://repair.dji.com/help/content?customId=01700008285&documentType=&lang=en&paperDocType=ARTICLE&re=US&spaceId=17).

Публичный MSDK использует `ILTEManager`:

- получение verification code;
- `startLTEAuthentication(...)`;
- обновление authentication info;
- проверка возможности включения LTE;
- переключение Enhanced Transmission между `OCU_SYNC` и `OCU_SYNC_LTE`.

В локально проверенном DJI MSDK 5.18 также присутствуют WLM link/service keys
и внутренний action `KeyRcEnable4G`. Перед переключением штатные delegate
проверяют `LTEService.canEnableLTE()` и возвращают `CAN_NOT_ENABLE_LTE`, если
policy/capability не разрешает операцию.

Официальный FAQ подтверждает дополнительные обязательные условия:

- совместимые aircraft/controller/dongle;
- SIM без PIN и рабочая регистрация в сети;
- актуальные firmware и DJI Fly;
- 4G на обеих сторонах;
- подписка и DJI relay server;
- включение Enhanced Transmission через DJI Fly.

`DERIVED`: штатный DJI 4G — это state machine:

```text
dongle detect/activate
  -> SIM/APN/dial
  -> peer dongle
  -> authentication/subscription
  -> pairing
  -> WLM ability/policy
  -> OCU_SYNC_LTE
  -> relay session
```

Одной универсальной безусловной команды «включить модем» в проверенном
официальном path нет.

## Сопоставление с текущим FreeFCC

### FCC

Наиболее сильные открытые признаки:

1. смена country через `07:30` подтверждена live-проверкой FreeFCC;
2. `09:27 00024800ffff0200000000` повторяется в нескольких открытых
   N1/N2-реализациях;
3. коммерческие приложения используют более широкий профиль и/или изменённый
   DJI Fly, но plaintext часто скрывают.

Это поддерживает отдельное A/B-тестирование минимального FCC core, но не
доказывает назначение каждого кадра полного профиля.

### 4G

`NEGATIVE`: последовательность FreeFCC `51:00..7F` не найдена в открытом виде
ни в NLD, ни в OpenFCC, ни в Drone-Hacks, ни в официальном MSDK path.

Отдельная кросс-проверка приёмной стороны записана в
[WLM_CMDSET_51.md](WLM_CMDSET_51.md). Основная dynamic command table
восстановлена в RC2 и RC Pro 2 v576; дополнительные device-manager таблицы и
ветвление `51:19` проверены также в WA530.

`OBSERVED`:

- destination `0xEE` соответствует `dji_wlm` (`0x0e07`), а не `dji_lte`
  (`0x0e06`);
- основная таблица набора `0x51` регистрируется в
  `duss_event_create_client_more_config` и имеет 82 слота (`0x00..0x51`):
  на RC2 есть 33 непустые записи, из них 28 с request-handler; на RC Pro 2
  v576 — 35 и 30 (`2E` netlink service и `2F` agent general control есть
  только у RC Pro 2);
- дополнительно тремя таблицами регистрируется device-manager sync
  `30/31/32/33/35/36`, из которых варианты взаимоисключающие;
- request sweep может войти в 32–33 handler RC2 либо 34–35 handler RC Pro 2;
  соответственно 95–96 либо 93–94 кадра не имеют request-handler. Пять
  основных записей (`05/07/1D/1F/24`) являются ACK-only;
- достижимые request-handler получают `00 00 00 + ASCII identity`, не
  соответствующий их разным контрактам; задеты в том числе link mode switch
  (`02`), route switch (`0F`), select target dev (`15`), modem on/off (`19`),
  power-control report (`1B`) и bind status (`22`);
- в наборе нет LTE **activation** handler, но есть `51:19`
  `wlm_modem_onoff_control` (power-control путь) и `51:42`
  `wlm_ability_nego_result_req` — приёмник для `lte_query_wlm_nego_result` из
  `dji_lte`; отдельно `dji_wlm` слушает три ID набора `0x18`
  (`37` peer state, `3B` rpt track, `47` i-frame for wifi);
- `NO_ACK_NEEDED` не позволяет отличить обработку от простой успешной записи
  в сокет.

`DERIVED`: текущий `51:00..7F` sweep не воспроизводит известный штатный либо
сторонний многостадийный 4G activation path. В проверенном RC2 handler
payload `51:19` не доходит до on/off-действия, а пользовательский live-send не
дал видимого эффекта. Это делает профиль неподтверждённым legacy research
sweep, но не позволяет переносить отрицательный результат на другие
controller/aircraft builds.

### Где находятся известные ограничения модема

Постановка задачи важна: цель кнопки *Send 4G Activation Frames* — включить
модем, который заблокирован ограничениями. Поэтому ключевой вопрос не «почему
sweep не повторяет штатный flow», а **что именно держит модем выключенным и
какие известные входы меняют эти условия**. Разбор `dji_lte` показывает, что
ограничение — не одиночный on/off-флаг, а несколько gate внутри самого
`dji_lte`. Их точная совокупность зависит от controller build.

`CONFIRMED` в RC2 `a3s` — blacklist gate по коду страны:

- `get_country_code_ack` (`lte_event_common.c`) берёт код страны и прогоняет
  через `bool_country_in_lte_black_list` (сравнение 4-байтового кода со
  списком) и `bool_country_in_single_frequency_cn_list`;
- при попадании в чёрный список флаг `b_black_list_country` (`[state+4]`)
  **устанавливается в `1`**; вне списка он сбрасывается в `0`. В обоих случаях
  вызываются `pair_reinit_liblte_all_channel`,
  `reinit_peer_service_info`, `reinit_peer_ipv6_info` — то есть LTE-каналы
  переинициализируются после изменения country state;
- этот же `b_black_list_country` — прямой вход авторизации: строки
  `set_liblte_auth_info failured … country_code:%s, b_black_list_country:%d
  simstate:%d` и `set_peer_bindinfo failured … b_black_list_country:%d`
  доказывают, что при «чёрной» стране auth/bind не проходит;
- диагностические коды HMS подтверждают семантику:
  `LTE_HMS_ERR_UAV_BLACKLIST_COUNTRY`,
  `LTE_HMS_ERR_SINGLE_COUNTRY_NETWORK_UNREACHABLE`,
  `LTE_HMS_ERR_SINGLE_COUNTRY_NOT_REGISTERED`.

`CONFIRMED` в RC Pro 2 build 576 — дополнительный region gate:
`dongle_display_control != 0 && lte_get_country_region() == non-CN` блокирует
передачу `AUTH_PARAM`. Этот build поэтому нельзя считать идентичным RC2.
Полный predicate и адреса записаны в
[`DJI_CELLULAR_MODEM_LIVE_MAP.md`](DJI_CELLULAR_MODEM_LIVE_MAP.md).

`CONFIRMED` — gate активации dongle (отдельно от региона): периодическая
`00:32` (`common_dongle_activate`) держит activation state, и при
`dial_check_device_active=true` неактивный dongle блокирует dial с логом
`device is not activated, dial not allowed` (см.
[`LTE_DUML_COMMAND_REFERENCE.md`](LTE_DUML_COMMAND_REFERENCE.md)).

`OBSERVED` — country state DUML-достижим:

- `get_country_code_ack` получает двухбайтовый ASCII country из ответа
  `07:19`, а не читает MCC непосредственно из показанного handler;
- live на RC Pro 2 `07:30=CN` изменил последующий `07:19` readback с `RU` на
  `CN`; на RC2 подтверждены обе смены `RU → CN → RU`;
- пока не доказано, что новый country state дошёл до внутреннего экземпляра
  `dji_lte`, изменил его auth predicate и привёл к построению LTE link.

`DERIVED`: sweep `0x51` напрямую не записывает `b_black_list_country`,
`dongle_display_control` или dongle activation state. Однако из этого нельзя
заключать, что sweep универсально не способен запустить косвенный переход
состояния либо что все builds имеют тот же contract. Практический вывод уже:
в проверенном RC2 `51:19` получает неподходящий payload, а наиболее доступный
кандидат для проверки region gate — ранее подтверждённая связка
`07:30` write + `07:19` readback, а не выдуманный короткий формат `51:19`.

`EXTERNAL REPORT`: [upstream issue #18](https://github.com/doesthings/FreeFCC/issues/18)
сообщает об успешной 4G-активации на M30T/RM700; upstream README помечает
Mavic 4 Pro/RC Pro 2 как совместимые.
Оба источника не содержат firmware/log evidence, позволяющего отделить эффект
128-frame sweep от optional controller OTA swap и штатного DJI flow. Поэтому
они учитываются как `REPORTED`, не как наш `CONFIRMED`, и одновременно не
могут быть опровергнуты статикой RC2/RC Pro 2/WA530.

`OBSERVED`: NLD и OpenFCC учитывают model/device identity; OpenFCC отдельно
использует aircraft serial и долговременную 4G-сессию.

`DERIVED`: текущая успешная запись 128 кадров FreeFCC подтверждает только
transport write. Она не подтверждает:

- снятие region/country gate;
- разрешение WLM service mode;
- dongle activation;
- authentication/subscription;
- pairing двух LTE endpoints;
- появление `lte_conn=1`/`lte_usable=1`;
- фактический video/control traffic через LTE.

По совокупности данных расширять `4g.json` дополнительными случайными
командами нецелесообразно. Сначала нужен plaintext capture работающего
стороннего решения либо официальный state/readback.

## Таблица ключевых выводов

| Claim | Level | Artifact/location | Evidence | Confidence |
|---|---|---|---|---|
| NLD имеет реальный DUML transport | `OBSERVED` | NLD APK/Dex/native | USB/AOA/RCLink, parser, response handling | Высокая |
| NLD поддерживает FCC примерно раз в 2 s | `OBSERVED` | `f2` coroutine в `2.0.0.6` | loop вызывает native-backed apply и делает delay `2000 ms` | Высокая |
| NLD действительно отправляет отдельную известную 4G-команду | `UNKNOWN` | `libnld-core.so` | plaintext профиля скрыт | Низкая |
| NLD `fcc.json`/`4g.json` описывают активный runtime | `NEGATIVE` | NLD `2.0.0.6` DEX/assets | все assets совпадают с FreeFCC history, но Java asset-read call site отсутствует | Высокая |
| OpenFCC имеет отдельный Always-on 4G path | `OBSERVED` | OpenFCC APK/native | `FourGHeartbeatService`, 4G native relay, aircraft SN | Высокая |
| OpenFCC controller OTA участвует в снятии controller-side ограничений | `OBSERVED` + `DERIVED` | desktop launcher, `firmware_update.zip` | Подтверждены full A/B downgrade OTA build 139 и flow применения; точная причинная связь с 4G не проверена live | Средняя |
| Drone Tweaks меняет DJI Fly | `OBSERVED` | vendor FAQ | поставщик прямо описывает modified official app | Высокая |
| Drone-Hacks FCC мешает 4G | `VENDOR CLAIM` | Drone-Hacks Wiki | полевое предупреждение без RF evidence | Низкая/средняя |
| `09:27` — публичный минимальный FCC-примитив | `OBSERVED` | три open-source реализации | одинаковый payload `0xffff0048=2` | Высокая |
| 21-frame FreeFCC-USB профиль универсален | `HYPOTHESIS` | GitHub JSON/README | профиль открыт, но hardware test отсутствует | Низкая |
| Текущий FreeFCC targeted `51:1A` включает Enhanced Transmission | `UNCONFIRMED`; request layout `CONFIRMED` | `profiles/4g.json`, `WLM_CMDSET_51.md`, firmware handlers | Старый 128-frame sweep удалён; текущий request просит HYBRID и читает response, но физическая 4G-активация не подтверждена live | Высокая для layout, низкая для результата |
| Штатный DJI LTE требует нескольких стадий | `OBSERVED` + `DERIVED` | MSDK, DJI FAQ, firmware docs | auth, capability, pairing, WLM, relay | Высокая |

## Следующий наиболее информативный эксперимент

### Первый шаг: проверить прохождение country state в `dji_lte`

Наиболее дешёвый полезный эксперимент продолжает уже подтверждённый
`07:30/07:19` path и закрывает одну конкретную неопределённость: видит ли
внутренний `dji_lte` изменённую страну и снимается ли соответствующая часть
auth predicate.

Порядок:

1. сохранить исходный `07:19` и параллельный `dji_lte` log/state;
2. отправить уже проверенный `07:30=CN`, затем подтвердить `07:19=CN`;
3. при наличии штатных аппаратных условий инициировать DJI LTE auth/Enhanced
   Transmission и записать `get_country_code_ack`, blacklist/region и
   `set_liblte_auth_info` события;
4. пассивно сохранить встреченные `51:19`, `51:1A`, `00:32` и набор `0x18`,
   не предполагая заранее, какой из них является переключателем;
5. восстановить исходную страну и подтвердить её новым `07:19`.

Ручная отправка урезанного `51:19` не нужна: все три проверенных handler
отвергают длину ≤ 7. Захват внутреннего `51:19` остаётся полезным, но локальный
proxy может не видеть весь межпроцессный DUSS-трафик, а штатное 4G-включение
может требовать обоих dongle, SIM/subscription и разрешённого DJI account.

### Полный эксперимент

Наибольшую неопределённость снимет один контролируемый plaintext capture
успешного 4G-включения NLD либо OpenFCC на лицензированном/пробном устройстве:

1. снять исходные country, WLM, dongle, pair и LTE states;
2. записать весь локальный DUSS/DUML stream до, во время и после нажатия 4G;
3. отдельно сохранить ACK/readback;
4. повторить только FCC без 4G и получить differential set;
5. перезагрузить controller/aircraft и проверить, что именно приложение
   восстанавливает;
6. не смешивать модели и controller firmware builds.

Такой capture позволит отделить:

- FCC-команды;
- Remote ID-команды;
- region/capability unlock;
- одноразовую 4G activation;
- периодический heartbeat;
- model/SN-dependent материал.

Без этого переносить скрытые профили сторонних приложений в FreeFCC было бы
гипотезой, а не воспроизводимой реализацией.
