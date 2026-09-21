// DVP12SE11R - LineStopMonitor (версия 2, уровневый сигнал аварии)
// Instruction List (Delta ISPSoft). Можно вводить в режиме "Command"/IL.
// Карта регистров: см. REGISTER_MAP.md

// --- Rung 1: начало аварии (фронт X0)
LDP   X0
SET   M10
INCP  D1
MOV   K0  D6

// --- Rung 2: конец аварии / запуск линии (спад X0)
LDF   X0
SET   M11
INCP  D2
MOV   D6  D7
MOV   K0  D6

// --- Rung 3: состояние в D0
LD    X0
MOV   K1  D0

LDI   X0
MOV   K0  D0

// --- Rung 4: измерение текущей длительности аварии (0.1 с)
LDP   M1012
AND   X0
INCP  D6

// --- Rung 5: диагностика входа в D3
LD    X0
MOV   K1  D3

LDI   X0
MOV   K0  D3

// --- Rung 6: квитирование причины от Android
LD=   D101 K1
MOV   D100 D5
INCP  D4
RST   M10
RST   M11
MOV   K0  D101
MOV   K0  D100

// --- Rung 7: heartbeat контроллера (1 Гц)
LDP   M1013
INCP  D103

END
