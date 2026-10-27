#!/usr/bin/env python3
"""Prepare the Don't Panic toggle.

Usage: prepare_dontpanic.py <rom> <out_inc>

DevEd's "Don't Panic" patch (romhacking.net #6879) replaces the 3 bytes at
file offset 0x4C68 (bank 1 $4C68) with NOPs. This reads the original
instruction bytes there from your ROM, makes sure they can safely run from
another address, and writes them as a db line for dontpanic_toggle.asm.
"""
import sys

SITE = 0x4C68
SIZE = 3

THREE = {0x01, 0x08, 0x11, 0x21, 0x31, 0xC2, 0xC3, 0xC4, 0xCA, 0xCC, 0xCD,
         0xD2, 0xD4, 0xDA, 0xDC, 0xEA, 0xFA}
TWO = {0x06, 0x0E, 0x10, 0x16, 0x18, 0x1E, 0x20, 0x26, 0x28, 0x2E, 0x30,
       0x36, 0x38, 0x3E, 0xC6, 0xCB, 0xCE, 0xD6, 0xDE, 0xE0, 0xE6, 0xE8,
       0xEE, 0xF0, 0xF6, 0xF8, 0xFE}
INVALID = {0xD3, 0xDB, 0xDD, 0xE3, 0xE4, 0xEB, 0xEC, 0xED, 0xF4, 0xFC, 0xFD}
# Relative jumps depend on where they run; STOP is a special case. Refuse them.
UNSAFE = {0x18, 0x20, 0x28, 0x30, 0x38, 0x10}


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    rom = open(sys.argv[1], "rb").read()
    orig = rom[SITE:SITE + SIZE]
    print("Original bytes at bank 1 $4C68:", orig.hex(" "))
    if orig == b"\x00" * SIZE:
        sys.exit("Don't Panic: this ROM already has the patch applied")

    i = 0
    while i < SIZE:
        op = orig[i]
        if op in INVALID or op in UNSAFE:
            sys.exit(f"Don't Panic: opcode {op:02X} at +{i} cannot be relocated")
        i += 3 if op in THREE else 2 if op in TWO else 1
    if i != SIZE:
        sys.exit("Don't Panic: the 3 bytes do not end on an instruction boundary")

    with open(sys.argv[2], "w") as f:
        f.write("        db " + ", ".join(f"${b:02x}" for b in orig) +
                "  ; original instruction(s) at $4C68\n")
    print("Wrote", sys.argv[2])


if __name__ == "__main__":
    main()
