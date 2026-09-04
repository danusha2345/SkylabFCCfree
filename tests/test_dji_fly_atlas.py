from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "tools"))

import dji_fly_atlas  # noqa: E402


class FlycEnumParserTest(unittest.TestCase):
    def test_extracts_literal_constant_handler_and_sentinel(self) -> None:
        source = """
public enum CmdSet {
    COMMON(0, null),
    FLYC(3, new UAVCmdSetBase() {
        public enum CmdIdType implements CmdIdInterface {
            GetPlaneName(52, true),
            GetPushHome(68, false, DataOsdGetPushHome.class),
            GetParamsByIndex(External.VALUE),
            Other(511);

            private int data;
        }
    }),
    GIMBAL(4, null);
}
"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "CmdSet.java"
            path.write_text(source, encoding="utf-8")
            rows = dji_fly_atlas.parse_flyc_enum(path, "test", {"External.VALUE": 241})

        self.assertEqual([row["declared_name"] for row in rows], [
            "GetPlaneName", "GetPushHome", "GetParamsByIndex", "Other"
        ])
        self.assertEqual([row["cmd_id"] for row in rows], [52, 68, 241, 511])
        self.assertEqual(rows[1]["handler_class"], "DataOsdGetPushHome")
        self.assertEqual(rows[-1]["is_sentinel"], 1)
        self.assertEqual({row["cmd_set_name"] for row in rows}, {"FLYC"})

    def test_keeps_unknown_constant_unresolved(self) -> None:
        source = """
FLYC(3, new UAVCmdSetBase() {
    public enum CmdIdType implements CmdIdInterface {
        UnknownCommand(Missing.VALUE),
        Other(511);
    }
}),
"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "CmdSet.java"
            path.write_text(source, encoding="utf-8")
            rows = dji_fly_atlas.parse_flyc_enum(path, "test", {})

        self.assertEqual(rows[0]["cmd_id"], "")
        self.assertEqual(rows[0]["raw_value"], "Missing.VALUE")

    def test_extracts_multiple_command_sets(self) -> None:
        source = """
COMMON(0, new UAVCmdSetBase() {
    public enum CmdIdType implements CmdIdInterface {
        GetVersion(1),
        Other(511);
    }
}),
FLYC(3, new UAVCmdSetBase() {
    public enum CmdIdType implements CmdIdInterface {
        SetHomePoint(49),
        Other(511);
    }
}),
"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "CmdSet.java"
            path.write_text(source, encoding="utf-8")
            rows = dji_fly_atlas.parse_cmdset_enums(path, "test", {})

        self.assertEqual(
            [(row["cmd_set_name"], row["cmd_set"], row["declared_name"]) for row in rows],
            [("COMMON", 0, "GetVersion"), ("COMMON", 0, "Other"),
             ("FLYC", 3, "SetHomePoint"), ("FLYC", 3, "Other")],
        )


if __name__ == "__main__":
    unittest.main()
