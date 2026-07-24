# Происхождение helper APK для DJI RC2

Дата проверки: 2026-07-24.

Проверен локальный архив `freefcc-helpers.zip`:

- размер содержимого APK: 16 589 690 bytes;
- SHA-256 архива:
  `f2f8d77ab384c9c0a0ebd9d6f00115b7dfbd3bbe0de56dca9967e83cf64b9fa2`.

## Состав и подписи

| Файл | Package / version | SHA-256 APK | Signer certificate SHA-256 | Вывод |
|---|---|---|---|---|
| `01_PackageInstaller.apk` | `com.android.packageinstaller`, `11` (`30`) | `523361acbe62587fa61e00a92369e87daa0d812232b8942deba67771ccf2633a` | `a4aa1cdd2ea580cbbe67486b5f6f3cfea83f488889995afa70793daa516687da` | DJI-signed system package; побайтно совпадает с RC Pro 2 OTA build 139 и 576 |
| `02_FileManager.apk` | `com.android.documentsui`, `0.20.12.23-7ab9a2e1` (`121`) | `b7a943cf1af7351da9135eeabfa3554f4ca5c9174ebcfb547e21bca030011b69` | `a4aa1cdd2ea580cbbe67486b5f6f3cfea83f488889995afa70793daa516687da` | DJI-signed DocumentsUI; точный исходный DJI build в локальном корпусе не найден |
| `03_ATVLauncher.apk` | `ca.dstudio.atvlauncher.pro`, `0.1.21-pro` | `4bd6891e6762907857b9ad3d3182af4eac05bba1e33a128ababb72796e9e9d27` | `00dab5f09ba1aa2eff972d1c1f5ad14a9172ce09c51c588d10f63bb7fa9f9eb2` | Сторонний launcher, не подписан DJI |
| `04_Edge Gestures.apk` | `com.ss.edgegestures`, `2.0.1` | `7c5c6ec02ba45f09a392b5249e0f1f668f285397dfd61657d566851075aa6864` | `3b61c2a82aff9f7652ffe0b04be3c8f248b5e1aa7063f1a3846f0cf5c778628a` | Стороннее приложение; текущему install flow не требуется |

DJI certificate DN двух первых APK:
`EMAILADDRESS=dji@dji.com, CN=DJI, OU=DJI, O=DJI, L=ShenZhen,
ST=GuangDong, C=CN`.

Подпись доказывает происхождение от владельца приватного DJI signing key, но
сама по себе не доказывает, из какой публичной прошивки был извлечён файл.

## Сопоставление с локальными OTA

`01_PackageInstaller.apk` побайтно совпал с:

- RC Pro 2 `V55.31.01.39/139`:
  `system/system/priv-app/PackageInstaller/PackageInstaller.apk`;
- RC Pro 2 `V55.31.05.76/576`:
  `system/system/priv-app/PackageInstaller/PackageInstaller.apk`.

Это сильное локальное доказательство, что первый helper взят из официального
DJI system image, а не только переподписан похожим сертификатом.

`02_FileManager.apk` использует DJI package `com.android.documentsui` и
содержит DJI provider `com.dji.providers.media.documents`. В имеющихся RC Pro 2
OTA лежит более ранний `dpad_documentsui.apk`:

| Поле | Helper | OTA build 139/576 |
|---|---|---|
| Version | `0.20.12.23-7ab9a2e1` (`121`) | `0.20.03.18-55be80c7` (`113`) |
| SHA-256 | `b7a943cf...011b69` | `6b46b41d...14e21` |
| Certificate | DJI `a4aa1cdd...687da` | тот же DJI certificate |

Следовательно, FileManager действительно DJI-signed, но взят из другой или
более новой DJI system build, отсутствующей в локальном корпусе. Поиск по
точной версии, provider и hashes не дал публично индексируемого совпадения;
это не позволяет назвать точную исходную модель/прошивку.

## Минимальная цепочка установки

Для текущего RC2 SD-card flow нужны:

1. `01_PackageInstaller` — устанавливается первым;
2. `02_FileManager` — даёт нормальный выбор APK после перезапуска;
3. `03_ATVLauncher` — открывает Files и затем SkylabFCCfree;
4. SkylabFCCfree APK.

`04_Edge Gestures` не нужен: после первого ручного запуска SkylabFCCfree
стартует своим boot receiver, а открыть интерфейс можно из постоянного
уведомления. Объединить DJI system packages и сторонний launcher в один обычный
APK без системной подписи/привилегий нельзя: Android не позволяет одному APK
установить или заменить другие packages и получить их platform permissions.

Архив содержит сторонние APK, поэтому право на их публичное
перераспространение и соответствующие лицензии нужно проверять отдельно от
технической подписи. Без такой проверки README не должен утверждать, что
архив полностью redistributable.
