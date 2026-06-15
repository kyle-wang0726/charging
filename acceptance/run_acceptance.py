#!/usr/bin/env python3
"""Run the teacher's acceptance events against the existing REST API."""
#  指令 python acceptance\run_acceptance.py --input test.xlsx

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import zipfile
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from xml.etree import ElementTree


class ApiError(RuntimeError):
    pass


EVENT_PATTERN = re.compile(
    r"^\(\s*([^,]+)\s*,\s*([^,]+)\s*,\s*([^,]+)\s*,\s*([^)]+)\s*\)$"
)
CELL_REFERENCE_PATTERN = re.compile(r"([A-Z]+)")
EXCEL_NAMESPACE = {"x": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
REL_NAMESPACE = {
    "r": "http://schemas.openxmlformats.org/package/2006/relationships"
}
DOCUMENT_REL_NAMESPACE = (
    "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
)


class AcceptanceRunner:
    def __init__(self, base_url: str, output_dir: Path, config: dict[str, Any]) -> None:
        self.base_url = base_url.rstrip("/")
        self.output_dir = output_dir
        self.config = config
        self.users: dict[str, int] = {}
        self.requests: dict[str, int] = {}
        self.details: list[dict[str, Any]] = []

    def api(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        query: dict[str, Any] | None = None,
    ) -> Any:
        url = f"{self.base_url}{path}"
        if query:
            url += "?" + urlencode(query)
        payload = None if body is None else json.dumps(body).encode("utf-8")
        request = Request(
            url,
            data=payload,
            method=method,
            headers={"Content-Type": "application/json"},
        )
        try:
            with urlopen(request, timeout=15) as response:
                result = json.loads(response.read().decode("utf-8"))
        except HTTPError as exc:
            text = exc.read().decode("utf-8", errors="replace")
            raise ApiError(f"HTTP {exc.code}: {text}") from exc
        except URLError as exc:
            raise ApiError(f"无法连接后端 {self.base_url}: {exc.reason}") from exc
        if not result.get("success"):
            raise ApiError(result.get("message", "接口返回失败"))
        return result

    def configure(self) -> None:
        self.api("POST", "/api/admin/config", self.config)
        self.api(
            "POST",
            "/api/admin/fault-strategy",
            {"strategy": "TIME_ORDER"},
        )

    def ensure_user(self, vehicle: str) -> int:
        if vehicle in self.users:
            return self.users[vehicle]
        credentials = {"username": vehicle, "password": "acceptance-test"}
        try:
            response = self.api("POST", "/api/auth/register", credentials)
        except ApiError as exc:
            if "username already exists" not in str(exc):
                raise
            response = self.api("POST", "/api/auth/login", credentials)
        user_id = int(response["data"]["userId"])
        self.users[vehicle] = user_id
        return user_id

    def request_id(self, vehicle: str) -> int:
        request_id = self.requests.get(vehicle)
        if request_id is None:
            raise ApiError(f"车辆 {vehicle} 没有成功创建的充电请求")
        return request_id

    def advance_to(self, event_time: str) -> None:
        response = self.api("GET", "/api/admin/time")
        system_time = datetime.fromisoformat(response["data"]["systemTime"])
        target = datetime.combine(system_time.date(), datetime.strptime(event_time, "%H:%M").time())
        minutes = int((target - system_time).total_seconds() // 60)
        if minutes < 0:
            raise ApiError(
                f"系统时间 {system_time.isoformat()} 已超过事件时间 {event_time}；"
                "请重启后端后重新运行验收程序"
            )
        if minutes:
            self.api("POST", "/api/admin/time/advance", {"minutes": minutes})

    def execute_event(self, event: dict[str, str]) -> Any:
        event_type = event["event_type"]
        target = event["target"]
        operation = event["operation"]
        value = float(event["value"])

        if event_type == "A" and operation in {"F", "T"}:
            user_id = self.ensure_user(target)
            response = self.api(
                "POST",
                "/api/user/request",
                {
                    "userId": user_id,
                    "mode": "FAST" if operation == "F" else "SLOW",
                    "requestKwh": value,
                    "batteryCapacityKwh": value,
                    "vehicleNumber": target,
                },
            )
            self.requests[target] = int(response["data"]["requestId"])
            return response

        if event_type == "A" and operation == "O":
            return self.api(
                "DELETE",
                "/api/user/request",
                query={
                    "userId": self.ensure_user(target),
                    "requestId": self.request_id(target),
                },
            )

        if event_type == "B" and operation == "O":
            return self.api(
                "POST",
                "/api/admin/pile-state",
                {"pileId": target, "state": "WORKING" if value == 1 else "FAULT"},
            )

        if event_type == "C" and operation == "O":
            return self.api(
                "PUT",
                "/api/user/request",
                {
                    "userId": self.ensure_user(target),
                    "requestId": self.request_id(target),
                    "requestKwh": value,
                    "batteryCapacityKwh": value,
                },
            )

        raise ApiError(f"不支持的事件: {event['raw_event']}")

    def snapshot(self, event: dict[str, str], response: Any) -> dict[str, Any]:
        time_response = self.api("GET", "/api/admin/time")
        piles_response = self.api("GET", "/api/admin/piles")
        active_requests = []
        for vehicle, user_id in self.users.items():
            requests_response = self.api(
                "GET",
                "/api/user/requests",
                query={"userId": user_id, "includeFinished": "false"},
            )
            for request in requests_response["data"]:
                active_requests.append({"vehicle": vehicle, **request})
        return {
            "sequence": int(event["sequence"]),
            "event_time": event["event_time"],
            "raw_event": event["raw_event"],
            "event_response": response,
            "system_time": time_response["data"]["systemTime"],
            "piles": piles_response["data"],
            "active_requests": active_requests,
        }

    def run(self, input_path: Path, sheet_name: str | None) -> None:
        self.configure()
        events = normalize_events(read_input_rows(input_path, sheet_name))

        for event in events:
            self.advance_to(event["event_time"])
            try:
                response = self.execute_event(event)
            except (ApiError, KeyError, ValueError) as exc:
                response = {"success": False, "message": str(exc), "data": None}
            detail = self.snapshot(event, response)
            self.details.append(detail)
            print(
                f"[{event['sequence']:>2}/{len(events)}] "
                f"{event['event_time']} {event['raw_event']} -> {response['message']}"
            )

        self.write_results()

    def write_results(self) -> None:
        self.output_dir.mkdir(parents=True, exist_ok=True)
        jsonl_path = self.output_dir / "acceptance_results.jsonl"
        with jsonl_path.open("w", encoding="utf-8") as handle:
            for detail in self.details:
                handle.write(json.dumps(detail, ensure_ascii=False) + "\n")

        csv_path = self.output_dir / "acceptance_results.csv"
        with csv_path.open("w", encoding="utf-8-sig", newline="") as handle:
            fieldnames = [
                "sequence",
                "event_time",
                "raw_event",
                "event_success",
                "event_message",
                "system_time",
                "pile_snapshot",
                "waiting_area_snapshot",
                "fault_waiting_snapshot",
            ]
            writer = csv.DictWriter(handle, fieldnames=fieldnames)
            writer.writeheader()
            for detail in self.details:
                writer.writerow(
                    {
                        "sequence": detail["sequence"],
                        "event_time": detail["event_time"],
                        "raw_event": detail["raw_event"],
                        "event_success": detail["event_response"]["success"],
                        "event_message": detail["event_response"]["message"],
                        "system_time": detail["system_time"],
                        "pile_snapshot": format_piles(detail["piles"]),
                        "waiting_area_snapshot": format_requests(
                            detail["active_requests"], "WAITING_AREA"
                        ),
                        "fault_waiting_snapshot": " > ".join(
                            f"{request['vehicle']}:{request['mode']}:"
                            f"{request['requestKwh']:.2f}kWh"
                            for request in detail["active_requests"]
                            if request.get("status") == "FAULT_DISPATCH"
                        ) or "-",
                    }
                )


def format_piles(piles: list[dict[str, Any]]) -> str:
    parts = []
    for pile in piles:
        cars = pile.get("queueCars", [])
        car_text = " > ".join(
            f"{car['vehicleNumber']}:{car['status']}:"
            f"{car.get('requestKwh', 0):.2f}kWh"
            for car in cars
        )
        parts.append(f"{pile['pileId']}[{pile['state']}]={car_text or '-'}")
    return " | ".join(parts)


def format_requests(requests: list[dict[str, Any]], queue_area: str) -> str:
    return " > ".join(
        f"{request['vehicle']}:{request['mode']}:{request['requestKwh']:.2f}kWh"
        for request in requests
        if request.get("queueArea") == queue_area
    ) or "-"


def normalize_events(rows: Any) -> list[dict[str, str]]:
    events = []
    required = {"event_time", "raw_event"}
    for row_number, raw_row in enumerate(rows, start=2):
        row = {str(key).strip(): (value or "").strip() for key, value in raw_row.items()}
        if not any(row.values()):
            continue
        if not required.issubset(row) or not row["event_time"] or not row["raw_event"]:
            raise ApiError(
                f"CSV 第 {row_number} 行必须包含 event_time 和 raw_event"
            )

        match = EVENT_PATTERN.match(row["raw_event"])
        if not match:
            raise ApiError(f"CSV 第 {row_number} 行事件格式无效: {row['raw_event']}")
        parsed_type, parsed_target, parsed_operation, parsed_value = match.groups()

        event = {
            "sequence": row.get("sequence") or str(len(events) + 1),
            "event_time": normalize_event_time(row["event_time"], row_number),
            "event_type": parsed_type.upper(),
            "target": parsed_target,
            "operation": parsed_operation.upper(),
            "value": parsed_value,
            "raw_event": row["raw_event"],
        }
        try:
            int(event["sequence"])
            float(event["value"])
        except ValueError as exc:
            raise ApiError(f"CSV 第 {row_number} 行包含无效数字: {exc}") from exc
        events.append(event)
    if not events:
        raise ApiError("CSV 中没有可执行事件")
    return events


def read_input_rows(input_path: Path, sheet_name: str | None = None) -> list[dict[str, str]]:
    suffix = input_path.suffix.lower()
    if suffix == ".csv":
        with input_path.open("r", encoding="utf-8-sig", newline="") as handle:
            return list(csv.DictReader(handle))
    if suffix == ".xlsx":
        return read_xlsx_rows(input_path, sheet_name)
    raise ApiError(f"不支持的输入文件类型: {input_path.suffix}，请使用 .csv 或 .xlsx")


def read_xlsx_rows(input_path: Path, sheet_name: str | None) -> list[dict[str, str]]:
    try:
        with zipfile.ZipFile(input_path) as workbook:
            shared_strings = read_shared_strings(workbook)
            sheets = read_sheet_paths(workbook)
            if sheet_name:
                sheets = [sheet for sheet in sheets if sheet[0] == sheet_name]
                if not sheets:
                    raise ApiError(f"Excel 中找不到工作表: {sheet_name}")

            for current_sheet_name, sheet_path in sheets:
                rows = read_sheet_values(workbook, sheet_path, shared_strings)
                event_rows = find_event_columns(rows)
                if event_rows is not None:
                    print(f"使用 Excel 工作表: {current_sheet_name}")
                    return event_rows
    except zipfile.BadZipFile as exc:
        raise ApiError(f"无效的 XLSX 文件: {input_path}") from exc
    raise ApiError("Excel 中找不到“时刻/事件”或“event_time/raw_event”列")


def read_shared_strings(workbook: zipfile.ZipFile) -> list[str]:
    if "xl/sharedStrings.xml" not in workbook.namelist():
        return []
    root = ElementTree.fromstring(workbook.read("xl/sharedStrings.xml"))
    return [
        "".join(node.text or "" for node in item.findall(".//x:t", EXCEL_NAMESPACE))
        for item in root.findall("x:si", EXCEL_NAMESPACE)
    ]


def read_sheet_paths(workbook: zipfile.ZipFile) -> list[tuple[str, str]]:
    workbook_root = ElementTree.fromstring(workbook.read("xl/workbook.xml"))
    relations_root = ElementTree.fromstring(workbook.read("xl/_rels/workbook.xml.rels"))
    relation_targets = {
        relation.attrib["Id"]: relation.attrib["Target"]
        for relation in relations_root.findall("r:Relationship", REL_NAMESPACE)
    }
    sheets = []
    for sheet in workbook_root.findall(".//x:sheet", EXCEL_NAMESPACE):
        relation_id = sheet.attrib[f"{{{DOCUMENT_REL_NAMESPACE}}}id"]
        target = relation_targets[relation_id].replace("\\", "/").lstrip("/")
        sheet_path = target if target.startswith("xl/") else f"xl/{target}"
        sheets.append((sheet.attrib["name"], sheet_path))
    return sheets


def read_sheet_values(
    workbook: zipfile.ZipFile, sheet_path: str, shared_strings: list[str]
) -> list[list[str]]:
    root = ElementTree.fromstring(workbook.read(sheet_path))
    rows = []
    for row in root.findall(".//x:sheetData/x:row", EXCEL_NAMESPACE):
        values: dict[int, str] = {}
        for cell in row.findall("x:c", EXCEL_NAMESPACE):
            reference = cell.attrib.get("r", "A1")
            column_letters = CELL_REFERENCE_PATTERN.match(reference).group(1)
            column_index = excel_column_index(column_letters)
            values[column_index] = read_cell_value(cell, shared_strings)
        if values:
            rows.append([values.get(index, "") for index in range(max(values) + 1)])
        else:
            rows.append([])
    return rows


def read_cell_value(cell: ElementTree.Element, shared_strings: list[str]) -> str:
    cell_type = cell.attrib.get("t")
    if cell_type == "inlineStr":
        return "".join(node.text or "" for node in cell.findall(".//x:t", EXCEL_NAMESPACE))
    value_node = cell.find("x:v", EXCEL_NAMESPACE)
    if value_node is None or value_node.text is None:
        return ""
    if cell_type == "s":
        return shared_strings[int(value_node.text)]
    if cell_type == "b":
        return "true" if value_node.text == "1" else "false"
    return value_node.text


def excel_column_index(column_letters: str) -> int:
    result = 0
    for letter in column_letters:
        result = result * 26 + ord(letter) - ord("A") + 1
    return result - 1


def find_event_columns(rows: list[list[str]]) -> list[dict[str, str]] | None:
    time_headers = {"时刻", "时间", "event_time"}
    event_headers = {"事件", "raw_event"}
    for header_index, row in enumerate(rows):
        normalized = [str(value).strip() for value in row]
        time_index = next(
            (index for index, value in enumerate(normalized) if value in time_headers), None
        )
        event_index = next(
            (index for index, value in enumerate(normalized) if value in event_headers), None
        )
        if time_index is None or event_index is None:
            continue

        result = []
        for data_row in rows[header_index + 1 :]:
            event_time = cell_at(data_row, time_index)
            raw_event = cell_at(data_row, event_index)
            if event_time and raw_event and EVENT_PATTERN.match(raw_event):
                result.append({"event_time": event_time, "raw_event": raw_event})
        return result
    return None


def cell_at(row: list[str], index: int) -> str:
    return str(row[index]).strip() if index < len(row) else ""


def normalize_event_time(value: str, row_number: int) -> str:
    try:
        excel_day_fraction = float(value)
        if 0 <= excel_day_fraction < 1:
            total_minutes = round(excel_day_fraction * 24 * 60)
            return f"{total_minutes // 60:02d}:{total_minutes % 60:02d}"
    except ValueError:
        pass
    for pattern in ("%H:%M", "%H:%M:%S"):
        try:
            return datetime.strptime(value, pattern).strftime("%H:%M")
        except ValueError:
            pass
    raise ApiError(f"CSV 第 {row_number} 行 event_time 格式无效: {value}")


def parse_args() -> argparse.Namespace:
    script_dir = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description="通过现有 REST API 执行充电系统验收用例")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--input", type=Path, default=script_dir / "acceptance_input.csv")
    parser.add_argument("--sheet", help="XLSX 工作表名称；省略时自动查找时刻/事件列")
    parser.add_argument("--output-dir", type=Path, default=script_dir / "results")
    parser.add_argument("--fast-piles", type=int, default=2)
    parser.add_argument("--slow-piles", type=int, default=3)
    parser.add_argument("--queue-length", type=int, default=3)
    parser.add_argument("--waiting-area-size", type=int, default=10)
    parser.add_argument("--fast-power", type=float, default=30)
    parser.add_argument("--slow-power", type=float, default=10)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    config = {
        "fastChargingPileNum": args.fast_piles,
        "slowChargingPileNum": args.slow_piles,
        "chargingQueueLen": args.queue_length,
        "waitingAreaSize": args.waiting_area_size,
        "fastPower": args.fast_power,
        "slowPower": args.slow_power,
    }
    try:
        AcceptanceRunner(args.base_url, args.output_dir, config).run(args.input, args.sheet)
    except (ApiError, KeyError, ValueError) as exc:
        print(f"验收运行失败: {exc}", file=sys.stderr)
        return 1
    print(f"验收运行完成，结果位于: {args.output_dir.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
