# Live-карта DJI cellular-модемов

Дата проверки: 2026-07-21.

Цель: зафиксировать USB/AT-поведение проверенных DJI cellular-модемов отдельно
от DUML/DUSS 4G-команд FreeFCC. Наличие интернет-соединения у модема не
доказывает, что aircraft link активирован, а успешная запись FreeFCC в
`/duss/mb/0x205` не настраивает SIM, APN или USB network composition.

Полные IMEI, IMSI и ICCID намеренно не сохраняются в репозитории.

## Краткий результат

| Устройство | Уровень | Результат |
|---|---|---|
| Fibocom `NL668T-GL`, physical Yota SIM | OBSERVED | SIM `25011...` выбрана, регистрация в Yota успешна, APN `yota.ru`, PDP CID 1 активен |
| Fibocom USB profile `30` | OBSERVED | Только закрытые/vendor AT-интерфейсы; host network interface отсутствует |
| Fibocom USB profile `31` | OBSERVED | Добавляется CDC ECM (`cdc_ether`); после `GTRNDIS=1,1` работает host networking |
| eSIM-устройство с двумя Quectel/QDC535EA functions | OBSERVED | Одинаковые firmware/USB identity, разные встроенные UICC/eUICC-профили, обе functions были без packet attach в РФ |
| OpenFCC desktop launcher | OBSERVED | Отдельный controller OTA flow; не является AT-активацией USB-модема |

## Fibocom NL668T-GL с физической Yota SIM

### Идентификация

| Поле | Значение |
|---|---|
| USB VID:PID | `2ca3:4009` |
| USB product | `Baiwang` |
| AT manufacturer/model | `Fibocom Wireless Inc.`, `NL668T-GL` |
| Firmware | `19906.5090.00.02.00.23` |
| SIM/operator | физическая Yota SIM, IMSI prefix `25011`, `COPS: "YOTA"` |
| APN/CID | CID 1, `IPV4V6`, `yota.ru` |

В profile `30` AT отвечал на USB interfaces 2 и 3. После перехода в profile
`31` AT-порты сохранились, а interfaces 4/5 образовали CDC ECM network device.
VID:PID не меняется, поэтому profile нельзя определять только по `lsusb`.

### Исходное состояние

```text
+GTUSBMODE: 30
+GTAUTOCONNECT: 0
+GTRNDIS: 0
+CEREG: 0,1
+CGATT: 1
+CGACT: 1,1
```

Модем уже зарегистрировался и получил carrier-side address, но в profile `30`
Linux не видел сетевого интерфейса. `AT+GTRNDIS=1,1` возвращал `ERROR`, пока
ECM-интерфейс отсутствовал.

### Рабочая последовательность

Перед изменением проверять, что SIM готова, сеть зарегистрирована и CID 1
активен:

```text
AT+CPIN?
AT+COPS?
AT+CEREG?
AT+CGATT?
AT+CGACT?
AT+CGDCONT?
AT+CGPADDR
```

Затем:

```text
AT+GTAUTOCONNECT=1
AT+GTUSBMODE=31
```

`GTUSBMODE=31` применился немедленно и переинициализировал USB. После повторного
открытия AT-порта:

```text
AT+GTRNDIS=1,1
AT+GTRNDIS?
```

Подтверждённый ответ:

```text
+GTRNDIS: 1,1,"<carrier-ip>","<primary-dns>","<secondary-dns>"
```

После `AT+RESET` все три настройки сохранились:

```text
+GTUSBMODE: 31
+GTAUTOCONNECT: 1
+GTRNDIS: 1,1,...
```

NetworkManager получил `192.168.225.2/24` от modem gateway
`192.168.225.1`. Live-проверка через modem interface:

- `77.88.8.8`: 0% packet loss;
- `https://ya.ru/`: HTTP 302;
- `https://vk.com/`: HTTP 302;
- carrier DNS и Yandex DNS разрешают `mail.ru`;
- встроенный DNS proxy `192.168.225.1` один раз дал timeout, поэтому единичный
  DNS timeout не считать потерей LTE attach.

После modem reset ECM MAC меняется, следовательно меняется и Linux interface
name (`enx...`). Автоматизацию нужно привязывать к USB path/VID:PID и
`cdc_ether`, а не к одному имени интерфейса.

На тестовом компьютере modem connection оставлен с route metric `700`, Wi-Fi -
`600`, чтобы cellular link не перехватывал основной default route.

### Откат

Начальные значения зафиксированы, но обратная последовательность на железе не
исполнялась после успешного теста:

```text
AT+GTRNDIS=0,1
AT+GTAUTOCONNECT=0
AT+GTUSBMODE=30
```

Считать этот блок ожидаемым откатом по симметрии AT API, а не live-verified
процедурой. `GTUSBMODE=30` должен снова переинициализировать USB.

## Quectel/QDC535EA eSIM-устройство

Одно eSIM-устройство через внутренний QinHeng USB hub экспонировало две
QDC535EA composite functions. Обе одновременно проходили состояние
`2ecc:3001 WUKONG`, затем перечислялись как `2ca3:4010 Mobile Composite Device
Bus` с RNDIS, diagnostic и двумя AT interfaces.

Общее:

| Поле | Результат |
|---|---|
| Model | `QDC535EA` |
| Firmware | `QDC535EA_ACNR01D08_01.001.01.007` / `1.057.067_Q_DJI` |
| USB serial | одинаковый `200806006809080000` |
| RNDIS MAC | одинаковый `00:0c:29:a3:9b:6d` |
| SIM state | оба `CPIN: READY` |
| Packet state | оба `CGATT: 0`, PDP inactive, IP отсутствует |

Различия:

- один профиль имел China Mobile IoT UICC (`46024...`, APN `cmnet`), ISD-R AID
  не открывался;
- второй имел eUICC/eSIM (`46011...`, APN `ctnet`), ISD-R AID успешно
  открывался;
- оба видели российские LTE-сети, но оставались в `LIMSRV` с reject causes
  `No Suitable Cells In tracking Area` или
  `Roaming not allowed in this tracking area`;
- ручной выбор Yota `25011` не дал attach.

Во время теста вставка внешней Yota SIM в предполагаемый physical-slot unit не
изменила IMSI/ICCID активного профиля. Это доказывает только, что внешний слот
не был выбран в той USB/topology конфигурации; это не доказывает отсутствие
слота или неисправность SIM.

Одинаковые USB serial и RNDIS MAC делают одновременную host-network эксплуатацию
двух QDC535EA functions конфликтной. Для диагностических скриптов их нужно
различать по физическому USB path за внутренним hub.

## Граница с FreeFCC и OpenFCC

Текущий FreeFCC не выполняет перечисленные Fibocom AT-команды. Его experimental
4G profile строит 128 DUML frames и пишет их в abstract DUSS socket
`/duss/mb/0x205` без ACK/readback. Это aircraft/controller command path, а не
настройка cellular bearer.

Исследованный OpenFCC Windows launcher имеет отдельный `fourg_swap` flow:
скачивает DJI-signed controller `update.zip`, передаёт его на пульт и применяет
через `update_engine_client`. После этого OpenFCC APK отправляет защищённые 4G
activation/heartbeat frames. Controller OTA нельзя смешивать с AT-настройкой
Fibocom.

Скачанный пользователем `OpenFCC-Setup-Windows.exe` побайтно совпал с
исследованным официальным artifact:

```text
SHA256 50bc1e3a1ac532af4ed54a0057960610e619ddfe0246233f134a5dd02d26b741
```

## Источники

- Fibocom Technical FAQ: `https://www.fibocom.com/en/problems/index_itemid_977_page_%7B%24wtl_pager%7D.html`
- FIBOCOM NL668 AT Commands User Manual, mirror:
  `https://device.report/m/a5e1582fe8b5d9da1208e235921c7255f45bbb07b4b6cb3751e9c282033b1574`
- OpenFCC Apps: `https://openfcc.app/apps`
- FreeFCC 4G code: `Profiles.load4g()`, `DumlTransport.sendFramesUnix()`,
  `FccViewModel.send4gActivationFrames()`.
