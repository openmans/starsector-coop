#!/usr/bin/env python3
"""Audit org.lwjgl.* references in Starsector engine jars.

Reads every .class entry of the given jars, parses its constant pool and reports
owner/name/descriptor triples whose owner is in the org/lwjgl namespace. Used to
size the Null-LWJGL shim: the shim must cover exactly these members (plus types in
their signatures) so the engine never hits NoSuchMethodError/NoSuchFieldError.

Usage: audit_lwjgl.py <jar> [<jar> ...]
"""
from __future__ import annotations

import io
import sys
import zipfile
from collections import defaultdict

CP_TAGS = {
    1: 'Utf8', 3: 'Integer', 4: 'Float', 5: 'Long', 6: 'Double', 7: 'Class',
    8: 'String', 9: 'Fieldref', 10: 'Methodref', 11: 'InterfaceMethodref',
    12: 'NameAndType', 15: 'MethodHandle', 16: 'MethodType', 17: 'Dynamic',
    18: 'InvokeDynamic', 19: 'Module', 20: 'Package',
}


class Reader:
    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0

    def u1(self):
        v = self.data[self.pos]
        self.pos += 1
        return v

    def u2(self):
        v = int.from_bytes(self.data[self.pos:self.pos + 2], 'big')
        self.pos += 2
        return v

    def u4(self):
        v = int.from_bytes(self.data[self.pos:self.pos + 4], 'big')
        self.pos += 4
        return v

    def skip(self, n):
        self.pos += n


def parse_constant_pool(r: Reader):
    count = r.u2()
    cp = [None] * count
    i = 1
    while i < count:
        tag = r.u1()
        if tag == 1:
            length = r.u2()
            cp[i] = ('Utf8', r.data[r.pos:r.pos + length].decode('utf-8', 'replace'))
            r.skip(length)
        elif tag in (7, 8, 16, 19, 20):
            cp[i] = (CP_TAGS[tag], r.u2())
        elif tag in (15,):
            cp[i] = (CP_TAGS[tag], r.u1(), r.u2())
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            cp[i] = (CP_TAGS[tag], r.u2(), r.u2())
        elif tag in (5, 6):
            cp[i] = (CP_TAGS[tag], r.u4(), r.u4())
            i += 1
        else:
            raise ValueError(f'unknown cp tag {tag} at {i}')
        i += 1
    return cp


def utf8(cp, idx):
    entry = cp[idx]
    if entry is None or entry[0] != 'Utf8':
        raise ValueError(f'cp[{idx}] is not Utf8: {entry}')
    return entry[1]


def class_name(cp, idx):
    entry = cp[idx]
    assert entry[0] == 'Class'
    return utf8(cp, entry[1])


def member_ref(cp, idx):
    entry = cp[idx]
    assert entry[0] in ('Fieldref', 'Methodref', 'InterfaceMethodref')
    owner = class_name(cp, entry[1])
    nat = cp[entry[2]]
    name = utf8(cp, nat[1])
    desc = utf8(cp, nat[2])
    return owner, name, desc, entry[0]


def audit_jar(path: str, wanted_prefix='org/lwjgl/'):
    refs = defaultdict(set)          # (owner, kind) -> {(name, desc)}
    users = defaultdict(set)         # (owner, name, desc) -> {user class}
    zf = zipfile.ZipFile(path)
    for entry in zf.namelist():
        if not entry.endswith('.class'):
            continue
        data = zf.read(entry)
        r = Reader(data)
        r.skip(8)                     # magic, minor, major
        cp = parse_constant_pool(r)
        self_name = entry[:-6]
        for idx, item in enumerate(cp):
            if item is None or item[0] not in ('Fieldref', 'Methodref', 'InterfaceMethodref'):
                continue
            owner, name, desc, kind = member_ref(cp, idx)
            if not owner.startswith(wanted_prefix):
                continue
            key = (owner, 'field' if kind == 'Fieldref' else 'method')
            refs[key].add((name, desc))
            users[(owner, name, desc)].add(self_name)
    return refs, users


def main(argv):
    total_owners = 0
    for jar in argv:
        refs, users = audit_jar(jar)
        print(f'===== {jar} =====')
        for (owner, kind) in sorted(refs):
            total_owners += 1
            print(f'-- {owner} [{kind}] {len(refs[(owner, kind)])}')
            for name, desc in sorted(refs[(owner, kind)]):
                print(f'   {name}{desc}')
        print()
    print(f'# distinct owner/kind pairs: {total_owners}')


if __name__ == '__main__':
    main(sys.argv[1:])
