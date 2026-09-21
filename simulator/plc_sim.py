"""
Эмулятор Delta DVP12SE11R для отладки Android-приложения без железа.

Повторяет ладдер-программу заказчика:
    X0 = сигнал аварии (уровень): 1 = авария, 0 = линия работает
    M1002 (первый скан) -> D0 = 0, D1 = 0
    X0 = 1              -> D0 = 1
    X0 = 0              -> D0 = 0
    X0 (спад)           -> INC D1
    D101 = 1            -> D5 = D100, INC D4, D101 = 0, D100 = 0

Modbus TCP slave (FC 0x03 / 0x06 / 0x10), порт по умолчанию 502.
Тест-хук D200: 1 -> поднять аварию (X0=1), 2 -> снять (X0=0).

Запуск:
    py -3 simulator/plc_sim.py --host 0.0.0.0 --port 502
Команды:
    a + Enter  -> поднять сигнал аварии (X0 = 1)
    r + Enter  -> снять сигнал (линия запущена, X0 = 0)
    d + Enter  -> дамп регистров
    q + Enter  -> выход

Без внешних зависимостей.
"""

import argparse
import socket
import socketserver
import struct
import sys
import threading
import time

MODBUS_D_BASE = 0x1000
TEST_INPUT_REG = 200  # D200: 1 = поднять аварию, 2 = снять


class PlcModel:
    """Регистры + логика сканирования контроллера."""

    def __init__(self, scan_interval=0.005):
        self.lock = threading.RLock()
        self.scan_interval = scan_interval
        self.D = {n: 0 for n in range(0, 128)}
        self._x0 = False
        self._x0_prev = False
        self._pending_alarm = None
        self._running = False
        self._thread = None

    # ---- внешние воздействия (аналог 24 В на входе) --------------------
    def set_alarm(self, on):
        with self.lock:
            self._pending_alarm = bool(on)

    # ---- один цикл скана ПЛК -------------------------------------------
    def scan(self):
        with self.lock:
            if self._pending_alarm is not None:
                self._x0 = self._pending_alarm
                self._pending_alarm = None

            if (not self._x0) and self._x0_prev:        # LDF X0
                self.D[1] = (self.D[1] + 1) & 0xFFFF    # INC D1

            self.D[0] = 1 if self._x0 else 0            # MOV X0 D0

            if self.D[101] == 1:                        # LD= D101 K1
                self.D[5] = self.D[100]                 # MOV D100 D5
                self.D[4] = (self.D[4] + 1) & 0xFFFF    # INC D4
                self.D[101] = 0
                self.D[100] = 0

            self._x0_prev = self._x0

    # ---- доступ к holding registers ------------------------------------
    def read_holding(self, address, quantity):
        with self.lock:
            return [self.D.get(address - MODBUS_D_BASE + i, 0) & 0xFFFF
                    for i in range(quantity)]

    def write_holding(self, address, value):
        with self.lock:
            number = address - MODBUS_D_BASE
            if number == TEST_INPUT_REG:
                command = value & 0xFFFF
                if command == 1:
                    self._pending_alarm = True
                elif command == 2:
                    self._pending_alarm = False
                self.D[TEST_INPUT_REG] = 0
                return
            self.D[number] = value & 0xFFFF

    # ---- фоновый скан ---------------------------------------------------
    def start(self):
        self._running = True
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()

    def _loop(self):
        while self._running:
            self.scan()
            time.sleep(self.scan_interval)

    def stop(self):
        self._running = False
        if self._thread:
            self._thread.join(timeout=1.0)

    def dump(self):
        with self.lock:
            keys = [0, 1, 4, 5, 100, 101]
            return {f"D{k}": self.D[k] for k in keys}


class _Handler(socketserver.BaseRequestHandler):
    def handle(self):
        plc = self.server.plc
        while True:
            header = self._recv_exact(7)
            if not header:
                return
            tx_id, proto, length, unit = struct.unpack(">HHHB", header)
            if length < 2:
                return
            pdu = self._recv_exact(length - 1)
            if not pdu:
                return

            func = pdu[0]
            try:
                if func == 0x03:
                    start, qty = struct.unpack(">HH", pdu[1:5])
                    values = plc.read_holding(start, qty)
                    data = b"".join(struct.pack(">H", v) for v in values)
                    resp = bytes([0x03, len(data)]) + data
                elif func == 0x06:
                    addr, val = struct.unpack(">HH", pdu[1:5])
                    plc.write_holding(addr, val)
                    resp = pdu[:5]
                elif func == 0x10:
                    start, qty = struct.unpack(">HH", pdu[1:5])
                    byte_count = pdu[5]
                    data = pdu[6:6 + byte_count]
                    for i in range(qty):
                        value = struct.unpack(">H", data[i * 2:i * 2 + 2])[0]
                        plc.write_holding(start + i, value)
                    resp = struct.pack(">BHH", 0x10, start, qty)
                else:
                    resp = bytes([func | 0x80, 0x01])
            except Exception:
                resp = bytes([func | 0x80, 0x04])

            out = struct.pack(">HHHB", tx_id, 0, len(resp) + 1, unit) + resp
            self.request.sendall(out)

    def _recv_exact(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.request.recv(n - len(buf))
            if not chunk:
                return None
            buf += chunk
        return buf


class ModbusServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, host, port, plc):
        self.plc = plc
        super().__init__((host, port), _Handler)


def local_ipv4_addresses():
    result = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127."):
                result.add(ip)
    except Exception:
        pass
    return sorted(result)


def main():
    parser = argparse.ArgumentParser(description="DVP12SE11R Modbus TCP emulator")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=502)
    args = parser.parse_args()

    plc = PlcModel()
    plc.start()

    try:
        server = ModbusServer(args.host, args.port, plc)
    except OSError as exc:
        plc.stop()
        print("НЕ УДАЛОСЬ ЗАНЯТЬ ПОРТ %d: %s" % (args.port, exc))
        print("Запустите на другом порту:  py -3 simulator/plc_sim.py --port 1502")
        return

    threading.Thread(target=server.serve_forever, daemon=True).start()

    print("=" * 60)
    print("PLC emulator (DVP12SE11R) слушает %s:%d, Modbus TCP" % (args.host, args.port))
    print("В приложении (Настройки -> IP) укажите один из адресов:")
    for ip in local_ipv4_addresses():
        print("    %s   (порт %d)" % (ip, args.port))
    print("    10.0.2.2   (если приложение в Android-эмуляторе на этом ПК)")
    print("    127.0.0.1  (если проброшен adb reverse tcp:%d tcp:%d)" % (args.port, args.port))
    print("=" * 60)

    interactive = sys.stdin is not None and sys.stdin.isatty()
    if interactive:
        print("Команды: a=авария (X0=1), r=запуск (X0=0), d=дамп, q=выход")

    try:
        while True:
            if interactive:
                try:
                    cmd = input().strip().lower()
                except EOFError:
                    interactive = False
                    print("stdin закрыт — продолжаю работать в фоне (Ctrl+C для выхода).")
                    continue
                if cmd == "a":
                    plc.set_alarm(True)
                    print("X0 = 1 (авария)")
                elif cmd == "r":
                    plc.set_alarm(False)
                    print("X0 = 0 (линия запущена)")
                elif cmd == "d":
                    print(plc.dump())
                elif cmd in ("q", "quit", "exit"):
                    break
            else:
                time.sleep(1.0)
    except KeyboardInterrupt:
        pass
    finally:
        server.shutdown()
        plc.stop()
        print("PLC emulator stopped.")


if __name__ == "__main__":
    main()
