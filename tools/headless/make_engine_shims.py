#!/usr/bin/env python3
"""Generate the two engine-level shims the headless server needs.

1. coop.headless.RealCombatMain
   The engine's com.fs.starfarer.combat.CombatMain, renamed. The launcher calls
   CombatMain.main(new String[]{w, h, fullscreen}); with three arguments the real class probes
   Toolkit.getScreenSize(), which throws under java.awt.headless or on a host with no X server, and
   the class's own catch answers with System.exit(1). Our source-level shim
   (src/headless/java/com/fs/starfarer/combat/CombatMain.java) forwards to the renamed copy and drops
   the arguments.

2. com.fs.starfarer.launcher.ooOO... (the launcher settings / screen-scale class)
   Its screen-scale method computes the scale from Toolkit.getDefaultToolkit().getScreenSize(), a
   call CombatMain makes unconditionally (Math.min of window scale and screen scale). That is the
   second and last screen probe on the boot path. The patch rewrites the six bytes

       invokestatic  Toolkit.getDefaultToolkit()Ljava/awt/Toolkit;
       invokevirtual Toolkit.getScreenSize()Ljava/awt/Dimension;

   into

       invokestatic  coop.headless.HeadlessScreen.size()Ljava/awt/Dimension;
       nop nop nop

   which keeps every byte offset (both instructions are three bytes, so no branch or exception-table
   offset moves) and removes the last dependency on a real display.

The output is a small committed jar (tools/headless/engine-shims.jar) rather than loose class files:
the patched launcher class has a 282-character name, which does not fit a Windows path, and a jar is
the only place it can be stored portably. Re-run after a game-version bump:

  python tools/headless/make_engine_shims.py --jar C:/@/Starsector/starsector-core/starfarer_obf.jar
"""
from __future__ import annotations

import argparse
import os
import struct
import sys
import zipfile

COMBAT_SRC = b'com/fs/starfarer/combat/CombatMain'
COMBAT_DST = b'coop/headless/RealCombatMain'

TOOLKIT_CLASS = b'java/awt/Toolkit'
HELPER_CLASS = b'coop/headless/HeadlessScreen'
HELPER_METHOD = b'size'
DIMENSION_DESC = b'()Ljava/awt/Dimension;'


class ConstantPool:
    """Parsed constant pool that can grow by a few entries and be re-serialised."""

    def __init__(self, data: bytes):
        assert data[:4] == b'\xca\xfe\xba\xbe'
        self.header = data[:8]
        self.count = int.from_bytes(data[8:10], 'big')
        self.entries = [None] * self.count
        pos = 10
        i = 1
        while i < self.count:
            tag = data[pos]
            start = pos
            pos += 1
            if tag == 1:
                length = int.from_bytes(data[pos:pos + 2], 'big')
                raw = data[pos + 2:pos + 2 + length]
                pos += 2 + length
                self.entries[i] = (tag, raw)
            elif tag in (7, 8, 16, 19, 20):
                pos += 2
                self.entries[i] = (tag, data[start + 1:pos])
            elif tag == 15:
                pos += 3
                self.entries[i] = (tag, data[start + 1:pos])
            elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
                pos += 4
                self.entries[i] = (tag, data[start + 1:pos])
            elif tag in (5, 6):
                pos += 8
                self.entries[i] = (tag, data[start + 1:pos])
                i += 1
            else:
                raise ValueError('cp tag %d' % tag)
            i += 1
        self.end = pos

    def utf8(self, idx):
        return self.entries[idx][1]

    def add_utf8(self, value: bytes) -> int:
        self.entries.append((1, value))
        return len(self.entries) - 1

    def add_class(self, name_idx: int) -> int:
        self.entries.append((7, struct.pack('>H', name_idx)))
        return len(self.entries) - 1

    def add_name_and_type(self, name_idx: int, desc_idx: int) -> int:
        self.entries.append((12, struct.pack('>HH', name_idx, desc_idx)))
        return len(self.entries) - 1

    def add_methodref(self, class_idx: int, nat_idx: int) -> int:
        self.entries.append((10, struct.pack('>HH', class_idx, nat_idx)))
        return len(self.entries) - 1

    def _utf8_index(self, value: bytes):
        for idx, entry in enumerate(self.entries):
            if entry is not None and entry[0] == 1 and entry[1] == value:
                return idx
        return None

    def find_methodref(self, owner: bytes, name: bytes, desc: bytes):
        """@return cp index of the Methodref, or None"""
        owner_idx = self._utf8_index(owner)
        name_idx = self._utf8_index(name)
        desc_idx = self._utf8_index(desc)
        if owner_idx is None or name_idx is None or desc_idx is None:
            return None
        class_idx = None
        for idx, entry in enumerate(self.entries):
            if entry is not None and entry[0] == 7 and struct.unpack('>H', entry[1])[0] == owner_idx:
                class_idx = idx
                break
        if class_idx is None:
            return None
        nat_idx = None
        for idx, entry in enumerate(self.entries):
            if entry is not None and entry[0] == 12:
                n, d = struct.unpack('>HH', entry[1])
                if n == name_idx and d == desc_idx:
                    nat_idx = idx
                    break
        if nat_idx is None:
            return None
        for idx, entry in enumerate(self.entries):
            if entry is not None and entry[0] in (10, 11):
                c, nat = struct.unpack('>HH', entry[1])
                if c == class_idx and nat == nat_idx:
                    return idx
        return None

    def serialize(self) -> bytes:
        out = bytearray(self.header)
        out += len(self.entries).to_bytes(2, 'big')
        for entry in self.entries[1:]:
            if entry is None:
                continue
            tag, raw = entry
            out.append(tag)
            if tag == 1:
                out += len(raw).to_bytes(2, 'big')
            out += raw
        return bytes(out)


def rename_combatmain(data: bytes) -> bytes:
    cp = ConstantPool(data)
    for idx, entry in enumerate(cp.entries):
        if entry is not None and entry[0] == 1 and COMBAT_SRC in entry[1]:
            cp.entries[idx] = (1, entry[1].replace(COMBAT_SRC, COMBAT_DST))
    return cp.serialize() + data[cp.end:]


def patch_screen_probe(data: bytes) -> bytes:
    cp = ConstantPool(data)
    get_default = cp.find_methodref(TOOLKIT_CLASS, b'getDefaultToolkit', b'()Ljava/awt/Toolkit;')
    get_size = cp.find_methodref(TOOLKIT_CLASS, b'getScreenSize', b'()Ljava/awt/Dimension;')
    if get_default is None or get_size is None:
        raise SystemExit('screen-probe methods not referenced by this class')
    helper_class = cp.add_class(cp.add_utf8(HELPER_CLASS))
    helper_nat = cp.add_name_and_type(cp.add_utf8(HELPER_METHOD), cp.add_utf8(DIMENSION_DESC))
    helper_ref = cp.add_methodref(helper_class, helper_nat)

    pattern = b'\xb8' + get_default.to_bytes(2, 'big') + b'\xb6' + get_size.to_bytes(2, 'big')
    replacement = b'\xb8' + helper_ref.to_bytes(2, 'big') + b'\x00\x00\x00'
    body = data[cp.end:]
    hits = body.count(pattern)
    if hits < 1:
        raise SystemExit('no Toolkit.getScreenSize call site found')
    body = body.replace(pattern, replacement)
    return cp.serialize() + body


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--jar', default=os.environ.get('STARFARER_OBF')
                    or 'C:/@/Starsector/starsector-core/starfarer_obf.jar')
    ap.add_argument('--out', default='tools/headless/engine-shims.jar')
    args = ap.parse_args(argv)

    zf = zipfile.ZipFile(args.jar)
    combat_prefix = COMBAT_SRC.decode()
    entries = [n for n in zf.namelist()
               if n[:-6] == combat_prefix or n.startswith(combat_prefix + '$')]
    if not entries:
        print('no CombatMain classes in %s' % args.jar, file=sys.stderr)
        return 1
    screen_class = None
    for name in zf.namelist():
        if not name.startswith('com/fs/starfarer/launcher/ooO') or not name.endswith('.class'):
            continue
        if b'getScreenSize' in zf.read(name):
            screen_class = name
            break
    if screen_class is None:
        print('screen-scale class not found in %s' % args.jar, file=sys.stderr)
        return 1

    out_path = args.out
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with zipfile.ZipFile(out_path, 'w', zipfile.ZIP_DEFLATED) as out:
        for entry in entries:
            suffix = entry[len(combat_prefix):-6]
            out.writestr(COMBAT_DST.decode() + suffix + '.class', rename_combatmain(zf.read(entry)))
        out.writestr(screen_class, patch_screen_probe(zf.read(screen_class)))

    print('wrote %d CombatMain classes + patched %s into %s'
          % (len(entries), screen_class, out_path))
    return 0


if __name__ == '__main__':
    sys.exit(main())
