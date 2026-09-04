# DJI Fly: как приложение узнаёт серийник и модель

См. также общий разбор настроек, actions и transport path:
[DJI Fly 1.21.10: взаимодействие с полётным контроллером](DJI_FLY_FLIGHT_CONTROLLER_INTERACTION.md).

Разбор APK `dji.go.v5`, снятого 2026-08-06 с живого пульта DJI RC Pro 2
(`rc520`, ADB, `pm path` → `adb pull`). Документ отделяет то, что буквально
написано в коде и ресурсах приложения, от выводов.

## Статус доказательств

- **OBSERVED** — прочитано непосредственно в коде, символах или ресурсах APK.
- **DERIVED** — однозначно следует из прочитанного.
- **HYPOTHESIS** — рабочее объяснение, требующее проверки.
- **NEGATIVE** — искомое отсутствует в явно указанном проверенном объёме; это
  не утверждение обо всех версиях приложения.

## Как добрались до кода

**OBSERVED.** Настоящего кода в `classes.dex` нет: там только упаковщик
`com.AppGuard.AppGuard` (2.2 МБ). Классы лежат в `lib/arm64-v8a/libdatajar.so`
(205 МБ, экспорт `_binary_dexdata0_start`) — 22 склеенных dex начиная со
смещения `0x500c`. Заголовки затёрты, и ровно первые `0x20000` байт каждого dex
зашифрованы: `string_ids` перестаёт быть монотонным точно на `dex_start+0x20000`
во всех 22 блобах. Заголовки восстановлены из уцелевшего `map_list`, у 17 dex
удалось восстановить и зашифрованную голову `string_ids` обходом `string_data`.
Итог: 17 валидных dex, 156 467 классов. Пять блобов (~10 % классов) не
восстановлены — их `type/proto/field/method_ids` целиком в зашифрованной зоне,
поэтому **никакое утверждение вида «в приложении такого нет» не является полным**.
Шифр AppGuard не вскрыт (постоянный XOR исключён).

Артефакты разбора: `~/storage/dji-fly-research/` (в репозиторий не попадают).

## Серийник: активный запрос `00:51`

**OBSERVED.** `libsdk_jni.so` сохранил типизированную таблицу команд в виде
C++-шаблонов `uav::core::uav_cmd_base_req<v, cmd_set, cmd_id, Req, Rsp>` —
449 записей. Среди них:

| Команда | Символ |
|---|---|
| `00:51` | `uav_general_get_fetch_serial_number_req` / `_rsp` |
| `02:90` | `uav_camera_serial_number_req` / `_rsp` |
| `03:33` | `uav_fc_set_aircraft_name_req` / `_rsp` |
| `03:34` | `uav_fc_get_aircraft_name_req` / `_rsp` |
| `03:AF` | `uav_fc_get_product_config_req` / `_rsp` |
| `03:B4` | `uav_fc_get_uav_uav_code_req` / `_rsp` |

**OBSERVED.** Все обработчики серийника (`CommonProductSerialNumberGet`,
`CommonFCSerialNumberGet`, `ProductSerialNumberGet`, `ProductUUIDGet`,
`ChipIdGet`, `EagleChipSerialNumberGet`, `RemoteControllerAbstraction::
GetSerialNumber`, `UAV77FlightControllerAbstraction::GetSerialNumber`,
`ActivateMgr::GetSerialNumber`) сходятся в один pack
`general_eagle_get_serial_number_pack`, отправляемый через `SendGetPack(...)`.
Это GET, а не подписка. Есть и отдельный action-ключ `ForceGetSerialNumber`,
минующий кэш. Разновидность серийника выбирается байтом-селектором в
`InternalGetSerialNumber(UAV_COMMON_SERIAL_NUMBER_TYPE, sender, receiver, …)`.

**DERIVED.** Наблюдавшийся нами «push серийника при заходе в «Информацию»» —
это ответ на этот GET, который экран About делает при открытии. Пассивно, без
чужого запроса, серийник в этом виде на шине не появляется.

**Что это значит для нас.** Экран «Информация» — не единственный источник:
живые дампы с обоих пультов показали, что S/N приходит ещё и в кадре `03:44`
(Home Point push), 16-символьным хвостом без префикса `1581`. См.
[DUML_STREAM_MAP.md](DUML_STREAM_MAP.md).

## Модель: `00:82` тут ни при чём

**NEGATIVE.** Шаблона `uav_cmd_base_req<1,0,130>` (`00:82`) в таблице нет, как
и `<1,0,129>` (`00:81`). Сканирование шаблонов по всем `lib/arm64-v8a/*.so`
даёт совпадения только в `libsdk_jni.so` (450) и `libdcl_jni.so` (2). С учётом
пяти невосстановленных dex это не абсолютное «нигде», но в нативном слое
протокола `00:82` отсутствует.

**DERIVED.** Трафик `00:82`, из которого мы читаем код продукта, порождает не
DJI Fly, а другой участник шины. Наше чтение от этого не становится неверным,
но это не тот путь, которым модель узнаёт сам DJI Fly.

**OBSERVED.** Модель в DJI Fly не читается одной командой, а собирается:
`uav::sdk::ProductTypeHandler::OnReceiveFCType` / `OnReceiveCameraType` /
`OnReceiveGimbalType` вместе с `DeviceDiscoverHandler::GetFcProductMap()`,
`GetGimbalProductMap()`, `GetCameraProductMap()`, питаемые «interconnect»-push
(`StartListenInterconnectPush`, `HandleInterconnectPushData`,
`INTERCONNECT_2_0`, лог `"not pulse push data, sub_cmd="`). Кандидаты в
таблице: `00:B8 function_discover`, `00:B7 static_cap`,
`00:99 united_pub_sub_agent`, `00:B6 exclusive_set_push` — **HYPOTHESIS**, какой
именно: `full_cmd_id` вычисляется в рантайме в `UavProtocolEncoder::GetFullCmdId`.

**OBSERVED.** Экран `about/device/SettingDeviceModelViewModel` показывает модель,
отыскивая `getDeviceId()` в **локальной** таблице имён. Строка модели на шину не
ходит вообще. Путь серийника на экране другой:
`fpvsetting.about.sn.SettingDroneSnViewModel` → `FlyModel.getAircraftDeviceInfo()
.getSerialNumber()` → `AircraftDeviceInfoModelImpl` →
`"Aircraft.AircraftDeviceInfo.SerialNumber"` → `UAVProductKey` → нативный
`00:51`.

**OBSERVED. Слой ключей MSDK v5.** `UAVProductKey` (ComponentType.PRODUCT=65534):
`SerialNumber` (get+listen), `AnonymousSerialNumber`, `ForceGetSerialNumber`
(action `Empty→String`), `ProductType`, `InternalProductType`, `ProductEdition`,
`ProductUUID`, `HWProductId`, `DeviceID`. `UAVFlightControllerKey` (=4):
`SerialNumber`, `AircraftName` (get+set+listen → нативные `AircraftNameGet/Set`
→ `03:34`/`03:33`), `DeviceID`.

**DERIVED.** `03:34` — это *имя аппарата*, которое владелец может изменить, а не
коммерческое название модели. Наш каталог не должен считать его моделью без
оговорок.

## Кадрирование и CRC

**OBSERVED.** CRC живут в `libsdk_base.so`: экспорты `calc_crc8`, `calc_crc16`,
`calc_crc16_ex`; таблица CRC-8 (256 Б) по смещению `0x20FEB0`, CRC-16 (512 Б) —
`0x20FFB0`. Обе **байт в байт совпадают** с `CRC8_TABLE` и `CRC16_TABLE` в
`app/src/main/java/com/freefcc/app/DumlTransport.kt`. Дубликаты в
`libsdk_jni.so` (`0x155F85D`, `0x155F95E`), больше нигде.

**OBSERVED.** Кодек: `uav::core::UavProtocolEncoder::Encode` / `GetFullCmdId` /
`NeedAck` / `GetNextSequenceNumber`; декодер `UavProtocolDecoder::Decode22346` /
`DecodeCommand` / `DecodeExtHeader` / `DecodeMultiInOne` / `HeaderXor`.
`HeaderXor` и `DecodeMultiInOne` нашему парсеру неизвестны — **HYPOTHESIS**, что
часть кадров мы из-за этого пропускаем; условие срабатывания `HeaderXor` не
установлено.

## Таблица «код продукта → официальное название»

Полный справочник вынесен в [DJI_PRODUCT_CODES.md](DJI_PRODUCT_CODES.md).
Здесь — только про источник и про то, как связаны две нумерации.

**OBSERVED.** Названия лежат в `resources.arsc`, ресурсы
`product_official_name_*`, ключ которых записан с префиксом класса устройства:
`UAV165`, `OPR175`, `GLS153`. Это форма записи ключа ресурса, а не код, под
которым устройство известно наружу.

**OBSERVED.** Две нумерации связывают имена шрифтовых ресурсов:
`font/fly_uav165_wa151`, `font/fly_uav159_wa530`, `font/fly_uav113_wm1615` и так
далее — 32 пары. Отсюда `WA151 = DJI Lito X1`, `WA530 = DJI Avata 360`,
`WA152 = DJI Lito 1`. Раньше это соответствие считалось ненайденным.

**DERIVED.** Коды с цифрой на конце (`WM1615`, `WM1617`, `WM2605`) — обычное
дело, поэтому шаблон кода модели в приложении обязан допускать последний символ
цифрой: иначе такой код принимается за серийный номер.

## Что осталось недоказанным

- Значения `UAV_COMMON_SERIAL_NUMBER_TYPE` и раскладка payload `00:51`.
- Какой именно interconnect cmd_id несёт тип продукта; содержимое
  `GetFcProductMap()`.
- Условие срабатывания `HeaderXor` и формат `DecodeMultiInOne`.
- Коды `WA…`/`WM…` для внутренних id 124, 125, 157, 164, 166, 198, 204, 289,
  310, 312, 411, 412 — пары в шрифтовых ресурсах для них нет.
- Соответствие кодов пультов (`rc331`, `rc520` на шине) внутренним `OPR…`.
- Пять dex (~10 % классов) не восстановлены.
