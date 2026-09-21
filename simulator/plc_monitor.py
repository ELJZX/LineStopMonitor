"""
Живой монитор регистров реального ПЛК DVP12SE11R по Modbus TCP.

Карта регистров — как в ладдер-программе заказчика:
    D0  — состояние (1 = авария, 0 = работа)
    D1  — счётчик запусков
    D4  — счётчик квитирований
    D5  — код последней причины

Запуск:
    py -3 simulator/plc_monitor.py --host 172.16.29.150 --port 502
Выход: Ctrl+C.
"""

import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from app_logic import ModbusTcpClient  # noqa: E402

MODBUS_D_BASE = 0x1000


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    parser = argparse.ArgumentParser(description="Live Modbus monitor for DVP12SE11R")
    parser.add_argument("--host", default="172.16.29.150")
    parser.add_argument("--port", type=int, default=502)
    parser.add_argument("--interval", type=float, default=1.0)
    parser.add_argument("--count", type=int, default=0, help="сколько строк вывести (0 = бесконечно)")
    args = parser.parse_args()

    client = ModbusTcpClient(args.host, args.port, timeout=3.0)

    print("Монитор ПЛК %s:%d  (Ctrl+C для выхода)" % (args.host, args.port))
    print("-" * 70)

    printed = 0
    while True:
        try:
            if not client.connected:
                client.connect()
                print("подключено к %s:%d" % (args.host, args.port))
            regs = client.read_holding_registers(MODBUS_D_BASE, 6)
        except Exception as exc:
            print("нет связи: %s" % exc)
            client.close()
            time.sleep(2.0)
            continue

        state = "АВАРИЯ" if regs[0] else "работа"
        print(
            "%s  D0=%d %-7s  запусков=%d  квитировано=%d  последняя причина=%d"
            % (time.strftime("%H:%M:%S"), regs[0], state, regs[1], regs[4], regs[5])
        )

        printed += 1
        if args.count and printed >= args.count:
            break
        time.sleep(args.interval)

    client.close()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nостановлено")
