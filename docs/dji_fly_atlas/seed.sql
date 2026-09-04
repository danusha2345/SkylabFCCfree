PRAGMA foreign_keys = ON;

INSERT OR REPLACE INTO meta(key, value) VALUES
    ('schema_version', '1'),
    ('atlas_name', 'DJI Fly Native Command and Capability Atlas');

INSERT OR IGNORE INTO products(slug, name) VALUES
    ('avata-360', 'DJI Avata 360'),
    ('generic-dji-fly', 'Общая поверхность DJI Fly');

INSERT OR IGNORE INTO fly_versions(version, notes) VALUES
    ('1.21.10', 'Основная разобранная новая версия DJI Fly'),
    ('legacy', 'Legacy Java/P3 protocol surface');

INSERT OR IGNORE INTO evidence(evidence_key, evidence_type, path, notes) VALUES
    ('fc-interaction-doc', 'documentation', 'docs/DJI_FLY_FLIGHT_CONTROLLER_INTERACTION.md', 'Основная карта DJI Fly -> FC'),
    ('homepoint-live-doc', 'live_test', 'docs/DJI_HOMEPOINT_HEIGHT_LIMIT.md', 'Live A/B Home Point и GPS-FDI'),
    ('avata-tests-doc', 'live_test', 'docs/DJI_AVATA360_INTERNAL_TESTS.md', 'Live и firmware evidence по диагностике Avata 360'),
    ('fly-12110-apk', 'apk', '.scratch/dji-fly-fc-audit-20260903/v1.21.10/', 'Декомпилированная DJI Fly 1.21.10'),
    ('wa530-autoflight', 'firmware', '/home/danik/storage/dji_fresh_fw/wa530_system_fs/bin/dji_autoflight', 'WA530 aircraft autoflight ELF');

INSERT OR IGNORE INTO commands(cmd_set, cmd_id, name, direction, operation_type, risk_level, summary) VALUES
    (3, 32,  'SendGpsToFlyc',                  'request',       'write',  'low',       'Передаёт в FC местоположение приложения/пульта, время и состояние сети.'),
    (3, 49,  'SetHomePoint',                   'request',       'action', 'medium',    'Запрашивает установку Home Point; FC отдельно проверяет GNSS validity.'),
    (3, 52,  'GetPlaneName',                   'request',       'read',   'read_only', 'Читает имя aircraft.'),
    (3, 60,  'GetFailsafeAction',              'request',       'read',   'read_only', 'Читает действие FC при потере связи.'),
    (3, 68,  'GetPushHome',                    'push',          'push',   'read_only', 'Передаёт Home Point, ESC state и связанные runtime flags.'),
    (3, 69,  'GetPushGpsSnr',                  'push',          'push',   'read_only', 'Асинхронно передаёт SNR GNSS channels.'),
    (3, 70,  'SetPushGpsSnr',                  'request',       'write',  'low',       'Включает или выключает подробный GPS-SNR push.'),
    (3, 147, 'SendGpsInfo',                    'request',       'write',  'low',       'Legacy external GPS information packet.'),
    (3, 181, 'FaultInject',                    'request',       'action', 'high',      'Открывает, отправляет или останавливает искусственную неисправность.'),
    (3, 182, 'GetPushFaultInject',             'push',          'push',   'read_only', 'Статус fault-injection/FDI test.'),
    (3, 237, 'SetEscEcho',                     'bidirectional', 'mixed',  'medium',    'Управляет ESC beep/echo и читает echo state.'),
    (3, 247, 'GetParamInfoByHash',             'request',       'read',   'read_only', 'Читает metadata FC parameter по hash.'),
    (3, 248, 'GetParamsByHash',                'request',       'read',   'read_only', 'Читает текущее значение FC parameter по hash.'),
    (3, 249, 'SetParamsByHash',                'request',       'write',  'high',      'Записывает FC parameter по hash; возможна EEPROM persistence.'),
    (16, 16, 'FSTestEnable',                   'request',       'write',  'high',      'Включает или выключает внутренний FSTest host.'),
    (16, 17, 'FSTestRunCase',                  'request',       'action', 'high',      'Запускает именованный factory/system test case.'),
    (16, 18, 'FSTestCaseState',                'request',       'read',   'read_only', 'Запрашивает состояние именованного FSTest case.'),
    (16, 19, 'FSTestCaseCount',                'request',       'read',   'read_only', 'Запрашивает количество доступных FSTest cases.'),
    (16, 20, 'FSTestCaseInfo',                 'request',       'read',   'read_only', 'Читает имя/описание FSTest case по индексу.'),
    (89, 2,  'SystemSelfDiagnosticCapability', 'request',       'read',   'read_only', 'Запрашивает доступные diagnostic parts и actions.'),
    (89, 4,  'SystemSelfDiagnosticExecute',    'request',       'action', 'high',      'Запускает выбранный self-diagnostic action.'),
    (89, 5,  'SystemSelfDiagnosticTerminate',  'request',       'action', 'medium',    'Останавливает активную diagnostic procedure.');

INSERT OR IGNORE INTO routes(command_id, sender, receiver, notes)
SELECT id, 'APP:0', 'FLYC:0', 'Обычный DJI Fly -> FC route' FROM commands WHERE cmd_set = 3;
INSERT OR IGNORE INTO routes(command_id, sender, receiver, notes)
SELECT id, 'APP:0', 'MVISION:4 (raw 0x92)', 'WA530 internal FSTest host' FROM commands WHERE cmd_set = 16;
INSERT OR IGNORE INTO routes(command_id, sender, receiver, notes)
SELECT id, 'APP:0', 'DM368:0 (raw 0x08)', 'General SDK diagnostic route' FROM commands WHERE cmd_set = 89;

INSERT OR IGNORE INTO payload_fields(command_id, message_kind, byte_offset, byte_size, name, data_type, description)
SELECT id, 'request', 0, 1, 'home_type', 'u8', 'AIRCRAFT/RC/custom Home Point type' FROM commands WHERE cmd_set=3 AND cmd_id=49;
INSERT OR IGNORE INTO payload_fields(command_id, message_kind, byte_offset, byte_size, name, data_type, description)
SELECT id, 'request', 1, 8, 'latitude', 'f64le', 'Latitude' FROM commands WHERE cmd_set=3 AND cmd_id=49;
INSERT OR IGNORE INTO payload_fields(command_id, message_kind, byte_offset, byte_size, name, data_type, description)
SELECT id, 'request', 9, 8, 'longitude', 'f64le', 'Longitude' FROM commands WHERE cmd_set=3 AND cmd_id=49;
INSERT OR IGNORE INTO payload_fields(command_id, message_kind, byte_offset, byte_size, name, data_type, description)
SELECT id, 'response', 0, 1, 'result', 'u8', 'Transport/action result' FROM commands WHERE cmd_set=3 AND cmd_id=49;
INSERT OR IGNORE INTO payload_fields(command_id, message_kind, byte_offset, byte_size, name, data_type, description)
SELECT id, 'response', 1, 1, 'reason', 'u8', '03 observed as GPS_NOT_READY' FROM commands WHERE cmd_set=3 AND cmd_id=49;

INSERT OR IGNORE INTO payload_fields(command_id, message_kind, byte_offset, byte_size, name, data_type, description)
SELECT id, 'request', 0, 1, 'enabled', 'bool', '00 disable, 01 enable' FROM commands WHERE cmd_set=3 AND cmd_id=70;
INSERT OR IGNORE INTO payload_fields(command_id, message_kind, byte_offset, byte_size, name, data_type, description)
SELECT id, 'push', 0, 32, 'gps_snr', 'u8[32]', 'GPS SNR values, display uses raw & 0x7F' FROM commands WHERE cmd_set=3 AND cmd_id=69;
INSERT OR IGNORE INTO payload_fields(command_id, message_kind, byte_offset, byte_size, name, data_type, description)
SELECT id, 'push', 32, 32, 'glonass_snr', 'u8[32]', 'Second GNSS set' FROM commands WHERE cmd_set=3 AND cmd_id=69;

INSERT OR IGNORE INTO payload_fields(command_id, message_kind, byte_offset, byte_size, name, data_type, description)
SELECT id, 'request', 0, 4, 'parameter_hash', 'bytes[4]', 'Hash bytes in wire order' FROM commands WHERE cmd_set=3 AND cmd_id IN (247,248,249);
INSERT OR IGNORE INTO payload_fields(command_id, message_kind, byte_offset, byte_size, name, data_type, description)
SELECT id, 'request', 4, NULL, 'value', 'typed bytes', 'Present only for write' FROM commands WHERE cmd_set=3 AND cmd_id=249;

INSERT OR IGNORE INTO payload_fields(command_id, message_kind, byte_offset, byte_size, name, data_type, description)
SELECT id, 'request', 0, 1, 'version', 'u8', 'Observed request prefix 01' FROM commands WHERE cmd_set=89 AND cmd_id=2;
INSERT OR IGNORE INTO payload_fields(command_id, message_kind, byte_offset, byte_size, name, data_type, description)
SELECT id, 'request', 1, 1, 'target', 'u8', 'Diagnostic target selector' FROM commands WHERE cmd_set=89 AND cmd_id=2;
INSERT OR IGNORE INTO payload_fields(command_id, message_kind, byte_offset, byte_size, name, data_type, description)
SELECT id, 'request', 2, 1, 'capability', 'u8', '01 SELF_TEST, 02 ACTION' FROM commands WHERE cmd_set=89 AND cmd_id=2;

INSERT OR IGNORE INTO implementations(command_id, fly_version_id, layer, symbol, source_path, notes)
SELECT c.id, v.id, 'java', 'DataFlycSetHomePoint', '', 'Legacy Java serializer'
FROM commands c, fly_versions v WHERE c.cmd_set=3 AND c.cmd_id=49 AND v.version='1.21.10';
INSERT OR IGNORE INTO implementations(command_id, fly_version_id, layer, symbol, source_path, notes)
SELECT c.id, v.id, 'java', 'DataFlycGetFsAction', '', 'Legacy Java getter'
FROM commands c, fly_versions v WHERE c.cmd_set=3 AND c.cmd_id=60 AND v.version='1.21.10';
INSERT OR IGNORE INTO implementations(command_id, fly_version_id, layer, symbol, source_path, notes)
SELECT c.id, v.id, 'firmware', 'nav_v1_fstest_enable', '/home/danik/storage/dji_fresh_fw/wa530_system_fs/bin/dji_autoflight', 'Symbol recovered from .gnu_debugdata'
FROM commands c, fly_versions v WHERE c.cmd_set=16 AND c.cmd_id=16 AND v.version='1.21.10';

INSERT OR IGNORE INTO product_support(command_id, product_id, fly_version_id, support_state, evidence_level, notes)
SELECT c.id, p.id, v.id, 'supported', 'observed', 'Live ACK 01 03 proves handler; action rejected because GPS was not ready.'
FROM commands c, products p, fly_versions v
WHERE c.cmd_set=3 AND c.cmd_id=49 AND p.slug='avata-360' AND v.version='1.21.10';
INSERT OR IGNORE INTO product_support(command_id, product_id, fly_version_id, support_state, evidence_level, notes)
SELECT c.id, p.id, v.id, 'unsupported', 'observed', 'DM368 returned E0 INVALID_CMD.'
FROM commands c, products p, fly_versions v
WHERE c.cmd_set=89 AND c.cmd_id=2 AND p.slug='avata-360' AND v.version='1.21.10';
INSERT OR IGNORE INTO product_support(command_id, product_id, fly_version_id, support_state, evidence_level, notes)
SELECT c.id, p.id, v.id, 'partial', 'observed', 'Implementation and host 0x92 exist, but RC2 shared injection route returned no ACK.'
FROM commands c, products p, fly_versions v
WHERE c.cmd_set=16 AND c.cmd_id BETWEEN 16 AND 20 AND p.slug='avata-360' AND v.version='1.21.10';

INSERT OR IGNORE INTO observations(command_id, product_id, observed_at, result, response_hex, evidence_level, evidence_id, notes)
SELECT c.id, p.id, '2026-09-03', 'rejected_gps_not_ready', '0103', 'observed', e.id,
       'Same result with without_gps_allowed=1 and with both GPS-FDI gates disabled; all parameters restored.'
FROM commands c, products p, evidence e
WHERE c.cmd_set=3 AND c.cmd_id=49 AND p.slug='avata-360' AND e.evidence_key='homepoint-live-doc';
INSERT OR IGNORE INTO observations(command_id, product_id, observed_at, result, response_hex, evidence_level, evidence_id, notes)
SELECT c.id, p.id, '2026-09-03', 'aircraft_name', '00444a4920417661746120333630', 'observed', e.id, 'Decoded: DJI Avata 360.'
FROM commands c, products p, evidence e
WHERE c.cmd_set=3 AND c.cmd_id=52 AND p.slug='avata-360' AND e.evidence_key='avata-tests-doc';
INSERT OR IGNORE INTO observations(command_id, product_id, observed_at, result, response_hex, evidence_level, evidence_id, notes)
SELECT c.id, p.id, '2026-09-03', 'invalid_cmd', 'e0', 'observed', e.id, 'SELF_TEST and ACTION queries both returned E0.'
FROM commands c, products p, evidence e
WHERE c.cmd_set=89 AND c.cmd_id=2 AND p.slug='avata-360' AND e.evidence_key='avata-tests-doc';

INSERT OR IGNORE INTO relationships(source_ref, relation, target_ref, evidence_id, notes)
SELECT 'cmd:03:31', 'REQUIRES', 'state:GNSS_VALID', id, 'FC rejects without a valid GNSS solution.' FROM evidence WHERE evidence_key='homepoint-live-doc';
INSERT OR IGNORE INTO relationships(source_ref, relation, target_ref, evidence_id, notes)
SELECT 'cmd:03:31', 'UPDATES', 'state:HOME_POINT', id, '' FROM evidence WHERE evidence_key='fc-interaction-doc';
INSERT OR IGNORE INTO relationships(source_ref, relation, target_ref, evidence_id, notes)
SELECT 'state:HOME_POINT', 'AFFECTS', 'state:HEIGHT_LIMIT_REASON', id, 'Runtime relationship; exact limiter remains state-machine driven.' FROM evidence WHERE evidence_key='homepoint-live-doc';
INSERT OR IGNORE INTO relationships(source_ref, relation, target_ref, evidence_id, notes)
SELECT 'cmd:03:F9', 'WRITES', 'entity:FC_PARAMETER', id, '' FROM evidence WHERE evidence_key='fc-interaction-doc';
INSERT OR IGNORE INTO relationships(source_ref, relation, target_ref, evidence_id, notes)
SELECT 'param:without_gps_allowed', 'DOES_NOT_BYPASS', 'check:SET_HOME_POINT_GPS_READY', id, 'Live A/B on Avata 360.' FROM evidence WHERE evidence_key='homepoint-live-doc';
INSERT OR IGNORE INTO relationships(source_ref, relation, target_ref, evidence_id, notes)
SELECT 'cmd:10:13', 'ROUTES_TO', 'host:MVISION_4_0x92', id, 'Exact host derived from fstest.json and observed bus traffic.' FROM evidence WHERE evidence_key='avata-tests-doc';
INSERT OR IGNORE INTO relationships(source_ref, relation, target_ref, evidence_id, notes)
SELECT 'cmd:59:02', 'RETURNS_ON_AVATA_360', 'ccode:E0_INVALID_CMD', id, '' FROM evidence WHERE evidence_key='avata-tests-doc';
