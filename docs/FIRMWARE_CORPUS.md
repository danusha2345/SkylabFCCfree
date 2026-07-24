# Локальный корпус прошивок и артефактов

Дата фиксации: 2026-07-24.

Этот файл задаёт границы воспроизводимого статического анализа FreeFCC. В
корпус входят только прошивки и приложения, которые пользователь ранее скачал
и сохранил на этом ноутбуке. Новые firmware images из Интернета в выводы не
подмешиваются. Интернет допустим только как дополнительная проверка публичных
названий и происхождения; доказательством поведения остаётся локальный
артефакт с hash/Build ID и адресом в бинарнике.

## Основные наборы

| Платформа | Локальный набор | Что доступно | Что уже доказано |
|---|---|---|---|
| DJI RC/RM510 | `fpga_tang_nano_9k_card_reader-spinal/.scratch/rc_rm510_20260723/` | `dji_wlm`, `dji_link`, `dji_sdrs_agent`, `libduml_frwk.so`, `libwlm.so`, `dji.json`, debugdata | Маршрутизация DUML, `09:EC`, `51:14` и часть link/control handlers |
| DJI RC Pro 2/RC520 | `FreeFCC/.scratch/rcpro2_4g_ota/` | Android OTA v139/v400/v440/v576, извлечённые system/vendor roots, ELF и configs | Владелец route `0xEE`, таблица `0x51`, точная семантика `51:1A`, LTE/WLM host IDs |
| WM260 | `fpga_tang_nano_9k_card_reader-spinal/.scratch/wm260/` | `system_2.img`, `vendor_2.img`, извлечённый `dji_perception` | Route `10:58` приходит в `bvision:0/perception_service`; точный handler пока не найден |
| WA341 | `fpga_tang_nano_9k_card_reader-spinal/.scratch/wa341_extract/` | Извлечённый `dji_perception` | Отрицательная проверка: это не `bvision:0`, поэтому нельзя переносить его handlers на WM260 `10:58` |
| WA234 | локальные debugdata/заметки в соседнем firmware-проекте | `.gnu_debugdata`, symbol evidence | Используется только там, где Build ID и конкретный модуль совпадают |
| DJI Fly 1.21.4 | `FreeFCC/.scratch/` и результаты разбора в `AVATA360_4G_RESEARCH.md` | APK и `libdongle_esim_core.so` | Штатный eSIM flow использует stateful `18:4B/4C`, а не sweep `51:00..7F` |
| Live captures | `FreeFCC/.scratch/` | LAN JSON/JSONL, OpenFCC logs, bounded captures | Runtime evidence хранится отдельно от статически восстановленной семантики |

Пути выше локальные и не предназначены для коммита. Проверяемые выводы,
Build ID и hashes переносятся в `docs/`.

## RC Pro 2: зафиксированные ELF

### V55.31.01.39 / build 139

| Файл | Размер | Build ID | SHA-256 |
|---|---:|---|---|
| `dji_wlm` | 1 947 992 | `41970d8e26ecf0edee6c78f4f9f7f5d7` | `971604883f503fcd0e64faa9b0af36997c7cc8d09178f9138308775e2fcc7cbe` |
| `dji_lte` | 1 539 864 | `89ef10b5be42571ec1a07572f046d6ec` | `27052d52c3727b255ee6edade2e108230bebe1df1c3a1d178c9e061ed20d88d5` |
| `dji_link` | 323 488 | `f7cbc1f0e86b2bf5e2b90b73a137d876` | `9599db7a3ce889e8ab90760fd38335a0a20ea00c6383d2d4efe5bddefe2bcbf6` |
| `dji_sdrs_agent` | 433 744 | `08f689d46530fcea6e2a02562d831871` | `199cee68533bea0ea1cab5a45b5649b5fd3be507558dc7e6c617700dca10ab70` |
| `libduml_frwk.so` | 2 436 712 | `812a018196001576cc2374b39a14ca31` | `389685c2567adae3b2cd6fdfef046b9008e6cea638b7c716986fd49a07a29703` |
| `libwlm.so` | 392 312 | `c685ea099e4afe7484417b83685036c2` | `8ab9ff20d3c642c5868e193f0bdcad798280857753a436267c738f82b6b8b7eb` |

### V55.31.05.76 / build 576

| Файл | Размер | Build ID | SHA-256 |
|---|---:|---|---|
| `dji_wlm` | 2 179 472 | `a0a736c567c361bdd1568aac3ac99722` | `0d62e3b3cf368de1fc7d019debfb60457765c4a68289eeaef48988967a0b7220` |
| `dji_lte` | 1 667 592 | `2ae6c041582be14d56507a2b70910792` | `6788276b77649e6717227ba6d70792d97fdcf135999b425b56af43db1546e8ec` |
| `dji_link` | 334 896 | `2dbd30ad611d554a413edc6cb589a10d` | `18f49b61804ea205b5112ad2c000708a25d0418e336e8802795fe665254dc87f` |
| `dji_sdrs_agent` | 460 336 | `957685b54ca3e029b7982c78b4c2b7d6` | `36337b3ae3e393fe5bbe1f7223dbaaaa61e7cd627093c7cb35021923fd123fe2` |
| `libduml_frwk.so` | 2 440 776 | `8969ea4d5dd7c2eb42b405f64990c297` | `6ff43122a88489e7cae6727637c2e4701bf6b63d48b4e306fe6fe808f13ec4a7` |
| `libwlm.so` | 412 864 | `15b96461fe04f99a3c24dfb03c966607` | `6d56f652079d0e712cf9a953a0c28c4fce31cde7c709c2d33c674aeead811993` |

Набор активных handlers command set `0x51` в `dji_wlm` совпадает между build
139 и 576. Это снижает риск, что вывод о `51:1A` относится только к одной
версии.

## Что означает `0x0205`

В `lte_cfg.json` обеих разобранных версий:

| Поле | ID | Смысл |
|---|---:|---|
| `service_module_id` | `0x0e06` | `dji_lte` |
| `local_wlm_host_id` | `0x0e07` | наземный `dji_wlm` |
| `peer_wlm_host_id` | `0x0907` | воздушный WLM peer |
| `local_router_host_id` | `0x0205` | локальный Android DUSS router |

Поэтому abstract socket `/duss/mb/0x205` — mailbox локального router, а не
доказательство наличия или выбора LTE-модема. Реальный адрес назначения
профиля FreeFCC — `0xEE`, то есть `vt_gnd:7`; его userspace-получатель в этом
корпусе — `dji_wlm`.

## Контейнеры `0205`

Найденные файлы с `0205` в имени являются крупными Android/Qualcomm OTA
контейнерами, а не firmware микроконтроллера:

| Версия | Файл | Размер | SHA-256 |
|---|---|---:|---|
| v400 | `sys_app_PRAK-2020-01_0205.bin` | 974 095 193 | `ce93fe07c83c48b9bacf0649a2790e3e79b47460487df38244a73595c0196aee` |
| v440 | `rc520_0205_v55.31.04.40_20251202.pro.fw_0205.bin` | 973 707 546 | `8774d92e1251188f9d4404d6e5d0e319935d5745cdff210fb7c88aff2383a74b` |
| v576 | `sys_app_PRAK-2020-01_0205.bin` | 973 886 886 | `b1cadab4cec25000a0f702acffcec90e7768bccbfe03f640edc1374f61a223ab` |

Manifest содержит обычные Android/Qualcomm partitions: `abl`, `aop`,
`bluetooth`, `boot`, `dsp`, `dtbo`, `modem`, `odm`, `product`, `system`,
`system_ext`, `vendor`, `vendor_boot`, `xbl` и связанные security partitions.
Отдельного RC MCU или transmission MCU image в этих OTA не найдено.

## Честные пробелы текущего корпуса

| Вопрос | Статус | Почему не закрыт |
|---|---|---|
| Точная функция `06:72` | `NEGATIVE` | Route до RC MCU через `/dev/ttyHS2` найден, но firmware самого получателя отсутствует |
| Точная функция `06:8C` | `NEGATIVE` | Route до `vt_air:0` найден, но firmware воздушного transmission MCU отсутствует |
| Точная функция WM260 `10:58` | `UNKNOWN` | Получатель `bvision:0/perception_service` доказан, но registration/handler в имеющемся `dji_perception` пока не локализован |
| Avata 360 aircraft-side eSIM handler | `ABSENT` | Отдельного Avata 360 eMMC/firmware dump в локальном корпусе нет |

`NEGATIVE` здесь означает не «команды нет», а «после проверки точного
получателя нужного исполняемого firmware в имеющемся корпусе нет». Закрыть
`06:72` и `06:8C` дальнейшим анализом Android OTA невозможно без другого уже
имеющегося или нового firmware микроконтроллера.

## Правила воспроизводимости

1. Не переносить имя команды между разными `cmd_set`, даже если совпадает
   `cmd_id`.
2. Не переносить handler между платформами без совпадения route, module role и
   Build ID/семейства бинарника.
3. Отделять sender evidence, router evidence и receiver-handler evidence.
4. Для каждого нового вывода фиксировать artifact, SHA-256, Build ID, функцию
   и адрес/registration table.
5. Runtime write completion не считать физическим эффектом без readback,
   штатного state transition или визуального подтверждения.
