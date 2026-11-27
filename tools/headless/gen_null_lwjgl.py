#!/usr/bin/env python3
"""Generate the Null-LWJGL shim sources from the real lwjgl.jar.

Why generated: the engine links against org.lwjgl.* by exact method descriptor.
A hand-written stub that misses one member dies at runtime with
NoSuchMethodError/NoSuchFieldError, and GL11 alone carries ~700 methods and ~700
constants. This tool reads the real class files (fields, constants, methods,
superclasses, interfaces), emits a complete stand-in for every class reachable
from the audit seeds, and applies behaviour policies only where it matters
(Display state, id counters, glGet* answers).

Usage:
  python tools/headless/gen_null_lwjgl.py [--out src/headless/java] [--check]

The output is committed; the build compiles it as `sourceSets.headless`.
"""
from __future__ import annotations

import argparse
import os
import struct
import sys
import zipfile

OPCODE_LENGTHS = {}

# Classes that must keep the real implementation: they are pure Java (no native
# methods, no native library loading) and their real behaviour is useful.
KEEP_PREFIXES = (
    'org/lwjgl/BufferUtils',
    'org/lwjgl/LWJGLException',
    'org/lwjgl/util/vector/',
    'org/lwjgl/util/ReadableVector',
    'org/lwjgl/util/glu/tessellation/GLUtessellatorImpl',
    'org/lwjgl/util/glu/GLUtessellatorCallbackAdapter',
    'org/lwjgl/util/glu/Sphere',
)

# Every org.lwjgl member the engine (starfarer_obf, fs.common_obf, fs.sound_obf,
# starfarer.api) and coop.jar reference, from tools/headless/audit_lwjgl.py.
SEEDS = (
    'org/lwjgl/Sys',
    'org/lwjgl/LWJGLUtil',
    'org/lwjgl/input/Keyboard',
    'org/lwjgl/input/Mouse',
    'org/lwjgl/input/Cursor',
    'org/lwjgl/openal/AL',
    'org/lwjgl/openal/AL10',
    'org/lwjgl/openal/ALC10',
    'org/lwjgl/openal/ALCdevice',
    'org/lwjgl/openal/EFX10',
    'org/lwjgl/opengl/Display',
    'org/lwjgl/opengl/DisplayMode',
    'org/lwjgl/opengl/PixelFormat',
    'org/lwjgl/opengl/Pbuffer',
    'org/lwjgl/opengl/Drawable',
    'org/lwjgl/opengl/GLContext',
    'org/lwjgl/opengl/ContextCapabilities',
    'org/lwjgl/opengl/GL11',
    'org/lwjgl/opengl/GL14',
    'org/lwjgl/opengl/ARBVertexBufferObject',
    'org/lwjgl/opengl/EXTFramebufferObject',
    'org/lwjgl/util/Display',
    'org/lwjgl/util/glu/GLU',
)

ACC_PUBLIC = 0x0001
ACC_PRIVATE = 0x0002
ACC_PROTECTED = 0x0004
ACC_STATIC = 0x0008
ACC_FINAL = 0x0010
ACC_SYNCHRONIZED = 0x0020
ACC_VOLATILE = 0x0040
ACC_BRIDGE = 0x0040
ACC_TRANSIENT = 0x0080
ACC_NATIVE = 0x0100
ACC_INTERFACE = 0x0200
ACC_ABSTRACT = 0x0400
ACC_STRICT = 0x0800
ACC_SYNTHETIC = 0x1000
ACC_ENUM = 0x4000


class Reader:
    def __init__(self, data):
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

    def take(self, n):
        v = self.data[self.pos:self.pos + n]
        self.pos += n
        return v


class ClassFile:
    def __init__(self, data):
        r = Reader(data)
        assert r.u4() == 0xCAFEBABE
        r.u2()
        r.u2()
        count = r.u2()
        cp = [None] * count
        i = 1
        while i < count:
            tag = r.u1()
            if tag == 1:
                ln = r.u2()
                cp[i] = ('Utf8', r.take(ln).decode('utf-8', 'replace'))
            elif tag in (7, 8, 16, 19, 20):
                cp[i] = (tag, r.u2())
            elif tag == 15:
                cp[i] = (tag, r.u1(), r.u2())
            elif tag in (3, 4):
                cp[i] = (tag, r.u4())
            elif tag in (9, 10, 11, 12, 17, 18):
                cp[i] = (tag, r.u2(), r.u2())
            elif tag in (5, 6):
                cp[i] = (tag, r.u4(), r.u4())
                i += 1
            else:
                raise ValueError('cp tag %d' % tag)
            i += 1
        self.cp = cp
        self.access = r.u2()
        self.name = self.cls(r.u2())
        sup = r.u2()
        self.super = self.cls(sup) if sup else None
        self.interfaces = [self.cls(r.u2()) for _ in range(r.u2())]
        self.fields = []
        for _ in range(r.u2()):
            acc = r.u2()
            name = self.utf(r.u2())
            desc = self.utf(r.u2())
            constant = None
            for _ in range(r.u2()):
                aname = self.utf(r.u2())
                alen = r.u4()
                if aname == 'ConstantValue':
                    constant = r.u2()
                else:
                    r.take(alen)
            self.fields.append((acc, name, desc, constant))
        self.methods = []
        for _ in range(r.u2()):
            acc = r.u2()
            name = self.utf(r.u2())
            desc = self.utf(r.u2())
            throws = []
            for _ in range(r.u2()):
                aname = self.utf(r.u2())
                alen = r.u4()
                if aname == 'Exceptions':
                    n = r.u2()
                    throws = [self.cls(r.u2()) for _ in range(n)]
                else:
                    r.take(alen)
            self.methods.append((acc, name, desc, throws))

    def utf(self, idx):
        return self.cp[idx][1]

    def cls(self, idx):
        return self.utf(self.cp[idx][1])

    def constant(self, idx):
        entry = self.cp[idx]
        tag = entry[0]
        if tag == 3:
            v = entry[1]
            return v - 0x100000000 if v >= 0x80000000 else v
        if tag == 4:
            return struct.unpack('>f', entry[1].to_bytes(4, 'big'))[0]
        if tag == 5:
            v = (entry[1] << 32) | entry[2]
            return v - (1 << 64) if v >= (1 << 63) else v
        if tag == 6:
            return struct.unpack('>d', ((entry[1] << 32) | entry[2]).to_bytes(8, 'big'))[0]
        if tag == 8:
            return self.utf(entry[1])
        raise ValueError('constant tag %d' % tag)


def parse_type(desc, i):
    c = desc[i]
    if c == '[':
        t, i = parse_type(desc, i + 1)
        return t + '[]', i
    if c == 'L':
        end = desc.index(';', i)
        return desc[i + 1:end].replace('/', '.'), end + 1
    simple = {'B': 'byte', 'C': 'char', 'D': 'double', 'F': 'float',
              'I': 'int', 'J': 'long', 'S': 'short', 'Z': 'boolean', 'V': 'void'}[c]
    return simple, i + 1


def parse_method(desc):
    assert desc[0] == '('
    i = 1
    params = []
    while desc[i] != ')':
        t, i = parse_type(desc, i)
        params.append(t)
    ret, _ = parse_type(desc, i + 1)
    return params, ret


def java_literal(value, ftype):
    if ftype == 'boolean':
        return 'true' if value else 'false'
    if ftype == 'char':
        return str(value)
    if ftype == 'long':
        return '%dL' % value
    if ftype == 'float':
        if value != value:
            return 'Float.NaN'
        if value in (float('inf'), float('-inf')):
            return 'Float.POSITIVE_INFINITY' if value > 0 else 'Float.NEGATIVE_INFINITY'
        return repr(float(value)) + 'f'
    if ftype == 'double':
        if value != value:
            return 'Double.NaN'
        if value in (float('inf'), float('-inf')):
            return 'Double.POSITIVE_INFINITY' if value > 0 else 'Double.NEGATIVE_INFINITY'
        return repr(float(value)) + 'd'
    if ftype == 'String' or ftype.endswith('String'):
        out = ['"']
        for ch in value:
            if ch == '"':
                out.append('\\"')
            elif ch == '\\':
                out.append('\\\\')
            elif ch == '\n':
                out.append('\\n')
            elif ch == '\r':
                out.append('\\r')
            elif ch == '\t':
                out.append('\\t')
            elif ord(ch) < 0x20 or ord(ch) > 0x7e:
                out.append('\\u%04x' % ord(ch))
            else:
                out.append(ch)
        out.append('"')
        return ''.join(out)
    if ftype in ('int', 'short', 'byte'):
        if value == -2147483648:
            return 'Integer.MIN_VALUE'
        return str(int(value))
    if ftype == 'Integer':
        return str(int(value))
    return str(value)


def method_modifiers(flags, interface=False):
    out = []
    if not interface:
        if flags & ACC_PUBLIC:
            out.append('public')
        elif flags & ACC_PROTECTED:
            out.append('protected')
        elif flags & ACC_PRIVATE:
            out.append('private')
    if flags & ACC_STATIC and not interface:
        out.append('static')
    if flags & ACC_FINAL and not interface:
        out.append('final')
    if flags & ACC_ABSTRACT and not interface:
        out.append('abstract')
    if flags & ACC_SYNCHRONIZED:
        out.append('synchronized')
    if flags & ACC_STRICT:
        out.append('strictfp')
    return ' '.join(out)


def modifiers(flags, cls_level=False, interface=False):
    out = []
    if not cls_level and not interface:
        if flags & ACC_PUBLIC:
            out.append('public')
        elif flags & ACC_PROTECTED:
            out.append('protected')
        elif flags & ACC_PRIVATE:
            out.append('private')
    elif cls_level:
        if flags & ACC_PUBLIC:
            out.append('public')
    if flags & ACC_STATIC and not interface:
        out.append('static')
    if flags & ACC_FINAL and not interface:
        out.append('final')
    if flags & ACC_ABSTRACT and not cls_level and not interface:
        out.append('abstract')
    if flags & ACC_SYNCHRONIZED:
        out.append('synchronized')
    if flags & ACC_VOLATILE:
        out.append('volatile')
    if flags & ACC_TRANSIENT:
        out.append('transient')
    if flags & ACC_STRICT:
        out.append('strictfp')
    return ' '.join(out)


def default_body(ret):
    if ret == 'void':
        return '        ;'
    if ret == 'boolean':
        return '        return false;'
    if ret in ('byte', 'short', 'char', 'int'):
        return '        return 0;'
    if ret in ('long',):
        return '        return 0L;'
    if ret == 'float':
        return '        return 0.0f;'
    if ret == 'double':
        return '        return 0.0d;'
    return '        return null;'


# ---------------------------------------------------------------- policies ---

DISPLAY_EXTRA_FIELDS = [
    '    /** Set by CoopHeadlessShutdown / the `stop` console command; mirrors window close. */',
    '    private static volatile boolean headlessCloseRequested;',
    '    /** Frame pacing for the headless tick; 0 disables (Display.sync drives itself). */',
    '    private static volatile long headlessLastFrameNanos;',
]

DISPLAY_EXTRA_METHODS = [
    '''    /**
     * Non-LWJGL extension: asks the run loop to unwind exactly like a window close.
     * Used by the headless console's `stop` command and the JVM shutdown hook path.
     */
    public static void requestClose() {
        headlessCloseRequested = true;
    }

    /** Non-LWJGL extension: target frames per second for update() pacing. */
    public static void setHeadlessTargetFps(int fps) {
        headlessTargetFps = fps <= 0 ? 0 : fps;
    }

    private static int headlessTargetFps = Integer.getInteger("coop.headlessTickRate", 30).intValue();
''',
]


def display_body(name, desc, ret, params, args):
    if name == 'create':
        return ('        if (current_mode != null) { width = current_mode.getWidth(); height = current_mode.getHeight(); }\n'
                '        else { current_mode = new DisplayMode(width > 0 ? width : 1024, height > 0 ? height : 768, 32, 60); width = current_mode.getWidth(); height = current_mode.getHeight(); }\n'
                '        window_created = true;\n'
                '        System.out.println("[headless] Display created " + width + "x" + height);')
    if name == 'isCreated':
        return '        return window_created;'
    if name == 'destroy':
        return ('        headlessCloseRequested = true;\n'
                '        window_created = false;')
    if name == 'isCloseRequested':
        return '        return headlessCloseRequested;'
    if name == 'isVisible' or name == 'isActive':
        return '        return true;'
    if name == 'isDirty' or name == 'wasResized' or name == 'isResizable':
        return '        return false;'
    if name == 'getWidth':
        return '        return width > 0 ? width : 1024;'
    if name == 'getHeight':
        return '        return height > 0 ? height : 768;'
    if name == 'getX' or name == 'getY':
        return '        return 0;'
    if name == 'getPixelScaleFactor':
        return '        return 1.0f;'
    if name == 'getDesktopDisplayMode':
        return '        return new DisplayMode(1920, 1080, 32, 60);'
    if name == 'getDisplayMode':
        return ('        if (current_mode == null) { current_mode = new DisplayMode(width > 0 ? width : 1024, height > 0 ? height : 768, 32, 60); }\n'
                '        return current_mode;')
    if name == 'getAvailableDisplayModes':
        return ('        DisplayMode cur = getDisplayMode();\n'
                '        return new DisplayMode[] { cur, new DisplayMode(800, 600, 32, 60), new DisplayMode(1024, 768, 32, 60),\n'
                '                new DisplayMode(1280, 720, 32, 60), new DisplayMode(1920, 1080, 32, 60) };')
    if name == 'setDisplayMode':
        return ('        current_mode = %s;\n'
                '        if (current_mode != null) { width = current_mode.getWidth(); height = current_mode.getHeight(); }' % args[0])
    if name == 'setDisplayModeAndFullscreen':
        return ('        current_mode = %s;\n'
                '        if (current_mode != null) { width = current_mode.getWidth(); height = current_mode.getHeight(); fullscreen = current_mode.isFullscreenCapable(); }' % args[0])
    if name == 'setFullscreen':
        return '        fullscreen = %s;' % args[0]
    if name == 'isFullscreen':
        return '        return fullscreen;'
    if name == 'setTitle':
        return '        title = %s; System.out.println("[headless] " + title);' % args[0]
    if name == 'getTitle':
        return '        return title;'
    if name == 'setIcon':
        return '        return 1;'
    if name == 'setLocation':
        return '        x = %s; y = %s;' % (args[0], args[1])
    if name == 'setVSyncEnabled' or name == 'setSwapInterval':
        return '        swap_interval = 1;'
    if name == 'getAdapter':
        return '        return "Headless";'
    if name == 'getVersion':
        return '        return "2.1 NullGL";'
    if name == 'sync':
        return ('        long target = 1000000000L / Math.max(1, %s);\n'
                '        long now = System.nanoTime();\n'
                '        long sleep = target - (now - headlessLastFrameNanos);\n'
                '        if (sleep > 0L) { try { Thread.sleep(sleep / 1000000L, (int) (sleep %% 1000000L)); } catch (InterruptedException ignored) { } }\n'
                '        headlessLastFrameNanos = System.nanoTime();' % args[0])
    if name == 'update':
        return ('        int target = headlessTargetFps;\n'
                '        long now = System.nanoTime();\n'
                '        if (target > 0) {\n'
                '            long budget = 1000000000L / target;\n'
                '            long sleep = budget - (now - headlessLastFrameNanos);\n'
                '            if (sleep > 1000000L) { try { Thread.sleep(sleep / 1000000L); } catch (InterruptedException ignored) { } }\n'
                '        }\n'
                '        headlessLastFrameNanos = System.nanoTime();')
    if name == 'getDrawable' or name == 'getParent':
        return '        return null;'
    return None


def display_mode_body(name, desc, ret, params, args):
    if name == 'toString':
        return '        return width + " x " + height + " x " + bpp + " @" + freq + "Hz";'
    if name == 'equals':
        return ('        if (!(%s instanceof DisplayMode)) { return false; }\n'
                '        DisplayMode o = (DisplayMode) %s;\n'
                '        return width == o.width && height == o.height && bpp == o.bpp && freq == o.freq;' % (args[0], args[0]))
    if name == 'hashCode':
        return '        return (width * 31 + height) * 31 + bpp;'
    return None


def display_mode_ctor(desc, params):
    if params == ['int', 'int']:
        return '        this.width = a0; this.height = a1; this.bpp = 32; this.freq = 60; this.fullscreen = false;'
    if params == ['int', 'int', 'int', 'int']:
        return '        this.width = a0; this.height = a1; this.bpp = a2; this.freq = a3; this.fullscreen = false;'
    if params == ['int', 'int', 'int', 'int', 'boolean']:
        return ('        this.width = a0; this.height = a1; this.bpp = a2; this.freq = a3; this.fullscreen = a4;')
    return None


def gl_body(cls, name, desc, ret, params, args):
    if name.startswith('glGen') or name.startswith('alGen'):
        if ret == 'int':
            return '        return coop.headless.lwjgl.NullGL.nextId();'
        if params and params[-1] == 'java.nio.IntBuffer':
            return '        coop.headless.lwjgl.NullGL.fillIds(%s);' % args[-1]
    if name == 'glGetInteger':
        if params == ['int']:
            return '        return coop.headless.lwjgl.NullGL.getInteger(%s);' % args[0]
        if params == ['int', 'java.nio.IntBuffer']:
            return '        coop.headless.lwjgl.NullGL.getInteger(%s, %s);' % (args[0], args[1])
    if name == 'glGetString':
        return '        return coop.headless.lwjgl.NullGL.getString(%s);' % args[0]
    if name == 'gluErrorString':
        return '        return "headless";'
    if name in ('glGetError', 'alGetError'):
        return '        return 0;'
    if name.startswith('glCheckFramebufferStatus'):
        return '        return 36053;'
    if name == 'glIsEnabled':
        return '        return false;'
    if name in ('alGetSourcei', 'alGetSourcef'):
        if ret == 'int':
            return '        return %s == 4112 ? 4116 : 0;' % args[0]
        return '        return 0.0f;'
    if name in ('alIsSource', 'alIsBuffer'):
        return '        return false;'
    if name in ('glMapBuffer', 'glMapBufferARB'):
        return '        return null;'
    if name in ('glCreateShader', 'glCreateProgram'):
        return '        return coop.headless.lwjgl.NullGL.nextId();'
    if cls == 'org/lwjgl/util/glu/GLU':
        if name == 'gluNewTess':
            return '        return new org.lwjgl.util.glu.tessellation.GLUtessellatorImpl();'
        if name == 'gluBuild2DMipmaps':
            return '        return 0;'
        if name == 'gluProject' or name == 'gluUnProject':
            return default_body(ret)
        if name == 'gluScaleImage':
            return '        return 0;'
    return None


def util_display_body(cls, name, desc, ret, params, args):
    if cls == 'org/lwjgl/util/Display':
        if name == 'getAvailableDisplayModes':
            return '        return org.lwjgl.opengl.Display.getAvailableDisplayModes();'
        if name == 'setDisplayMode':
            return ('        org.lwjgl.opengl.DisplayMode chosen = null;\n'
                    '        if (%s != null && %s.length > 0) { chosen = %s[0]; }\n'
                    '        if (chosen == null) { chosen = org.lwjgl.opengl.Display.getDisplayMode(); }\n'
                    '        org.lwjgl.opengl.Display.setDisplayMode(chosen);\n'
                    '        return chosen;' % (args[0], args[0], args[0]))
        if name == 'setTitle':
            return '        org.lwjgl.opengl.Display.setTitle(%s);' % args[0]
    return None


def sys_body(name, desc, ret, params, args):
    if name in ('getTime',):
        return '        return System.nanoTime() / 1000000L;'
    if name == 'getTimerResolution':
        return '        return 1000000L;'
    if name == 'alert':
        return ('        System.err.println("[headless] ALERT " + %s + ": " + %s);' % (args[0], args[1]))
    if name == 'getVersion':
        return '        return "Starsector headless (Null-LWJGL)";'
    if name == 'openURL':
        return '        System.out.println("[headless] openURL ignored: " + %s);\n        return false;' % args[0]
    if name == 'create':
        return '        ;'
    if name == 'getClipboard':
        return '        return null;'
    return None


def input_body(name, desc, ret, params, args):
    if name == 'isCreated':
        return '        return true;'
    if name == 'create' or name == 'destroy' or name == 'poll' or name == 'enableRepeatEvents':
        return '        ;'
    if name == 'getNumKeyboardEvents' or name == 'getNumMouseButtons' or name == 'getNumButtons':
        return '        return 0;'
    if name == 'getEventCharacter':
        return "        return '\\u0000';"
    return None


def al_body(name, desc, ret, params, args):
    if name == 'isCreated':
        return '        return true;'
    if name in ('create', 'destroy'):
        return '        ;'
    return None


def lif_util_body(name, desc, ret, params, args):
    if name == 'log':
        return '        ;'
    if name == 'getPlatform':
        return '        return System.getProperty("os.name", "").toLowerCase().contains("win") ? 2 : 1;'
    if name == 'isMacOSX':
        return '        return System.getProperty("os.name", "").toLowerCase().contains("mac");'
    return None


CLASSES = {}


class Emitter:
    def __init__(self, classfile):
        self.cf = classfile
        self.name = classfile.name
        self.simple = self.name.rsplit('/', 1)[-1]
        self.is_interface = bool(classfile.access & ACC_INTERFACE)

    def field_lines(self):
        lines = []
        finals_without_init = []
        for acc, fname, desc, constant in self.cf.fields:
            ftype, _ = parse_type(desc, 0)
            mods = modifiers(acc, interface=self.is_interface)
            init = ''
            if constant is not None:
                init = ' = ' + java_literal(self.cf.constant(constant), ftype)
            elif acc & ACC_FINAL:
                if self.is_interface:
                    init = ' = ' + java_literal(0 if ftype in ('int', 'short', 'byte', 'char') else
                                                (False if ftype == 'boolean' else
                                                 (0.0 if ftype in ('float', 'double') else None)), ftype)
                elif acc & ACC_STATIC:
                    init = ' = ' + default_field_value(ftype)
                else:
                    finals_without_init.append((fname, ftype))
            lines.append('    %s%s %s%s;' % (mods + ' ' if mods else '', ftype, fname, init))
        return lines, finals_without_init

    def constructor_super_call(self):
        """Explicit super(...) when the real superclass has no no-arg constructor.

        Generated constructors do not replicate the original body, so an implicit super() only
        compiles when the superclass happens to offer one. Several LWJGL classes (References,
        Pbuffer, ...) do not, and javac would otherwise refuse the class outright.
        """
        sup = self.cf.super
        if not sup or sup == 'java/lang/Object':
            return None
        supcf = CLASSES.get(sup)
        if supcf is None:
            return None
        ctors = [m for m in supcf.methods if m[1] == '<init>' and not (m[0] & ACC_PRIVATE)]
        if not ctors:
            return None
        if any(not parse_method(c[2])[0] for c in ctors):
            return None
        best = min(ctors, key=lambda c: len(parse_method(c[2])[0]))
        params, _ = parse_method(best[2])
        return '        super(%s);' % ', '.join(default_field_value(t) if t != 'java.lang.String' else 'null'
                                               for t in params)

    def method_lines(self, finals_without_init):
        lines = []
        seen = set()
        super_call = self.constructor_super_call()
        for acc, mname, desc, throws in self.cf.methods:
            if mname == '<clinit>':
                continue
            if acc & ACC_BRIDGE:
                continue
            params, ret = parse_method(desc)
            key = (mname, tuple(params))
            if key in seen:
                continue
            seen.add(key)
            args = ['a%d' % i for i in range(len(params))]
            arglist = ', '.join('%s %s' % (t, a) for t, a in zip(params, args))
            mods = method_modifiers(acc, interface=self.is_interface)
            head = '%s %s' % (ret, mname) if mname != '<init>' else self.simple
            throws_clause = ''
            if throws:
                throws_clause = ' throws ' + ', '.join(t.replace('/', '.') for t in throws)
            sig = '    %s%s(%s)%s' % (mods + ' ' if mods else '', head, arglist, throws_clause)
            if self.is_interface:
                lines.append(sig + ';')
                continue
            body = self.policy(mname, desc, ret, params, args)
            if body is None:
                if mname == '<init>':
                    assigns = ['        this.%s = %s;' % (fn, default_field_value(ft))
                               for fn, ft in finals_without_init]
                    if super_call:
                        assigns.insert(0, super_call)
                    body = '\n'.join(assigns) if assigns else (super_call or '        ;')
                elif acc & ACC_ABSTRACT:
                    lines.append(sig + ';')
                    continue
                else:
                    body = default_body(ret)
            lines.append(sig + ' {')
            lines.append(body)
            lines.append('    }')
        return lines

    def policy(self, name, desc, ret, params, args):
        cls = self.name
        if cls == 'org/lwjgl/opengl/Display':
            return display_body(name, desc, ret, params, args)
        if cls == 'org/lwjgl/opengl/DisplayMode':
            if name == '<init>':
                return display_mode_ctor(desc, params)
            return display_mode_body(name, desc, ret, params, args)
        if cls == 'org/lwjgl/opengl/GLContext':
            if name == 'getCapabilities':
                return '        return HEADLESS_CAPS;'
            if name == 'isCurrent':
                return '        return true;'
        if cls == 'org/lwjgl/opengl/ContextCapabilities':
            return None
        if cls == 'org/lwjgl/Sys':
            b = sys_body(name, desc, ret, params, args)
            if b is not None:
                return b
        if cls == 'org/lwjgl/LWJGLUtil':
            b = lif_util_body(name, desc, ret, params, args)
            if b is not None:
                return b
        if cls.startswith('org/lwjgl/input/'):
            b = input_body(name, desc, ret, params, args)
            if b is not None:
                return b
        if cls.startswith('org/lwjgl/openal/'):
            b = al_body(name, desc, ret, params, args)
            if b is not None:
                return b
        if cls == 'org/lwjgl/util/Display':
            b = util_display_body(cls, name, desc, ret, params, args)
            if b is not None:
                return b
        if cls == 'org/lwjgl/util/glu/GLU':
            b = gl_body(cls, name, desc, ret, params, args)
            if b is not None:
                return b
        if cls.startswith('org/lwjgl/opengl/'):
            b = gl_body(cls, name, desc, ret, params, args)
            if b is not None:
                return b
        return None

    def tail_extra(self):
        extra = []
        if self.name == 'org/lwjgl/opengl/Display':
            extra.extend(DISPLAY_EXTRA_FIELDS)
        if self.name == 'org/lwjgl/opengl/GLContext':
            extra.append('    private static final ContextCapabilities HEADLESS_CAPS = createCaps();')
            extra.append('    private static ContextCapabilities createCaps() {')
            extra.append('        try { return new ContextCapabilities(true); }')
            extra.append('        catch (org.lwjgl.LWJGLException ex) { throw new RuntimeException(ex); }')
            extra.append('    }')
        return extra

    def emit(self):
        cf = self.cf
        lines = []
        lines.append('// GENERATED by tools/headless/gen_null_lwjgl.py from the game\'s lwjgl.jar.')
        lines.append('// Do not edit by hand; rerun the generator against a new lwjgl.jar instead.')
        lines.append('package %s;' % self.name.rsplit('/', 1)[0].replace('/', '.'))
        lines.append('')
        decl = []
        if cf.access & ACC_PUBLIC:
            decl.append('public')
        if cf.access & ACC_ABSTRACT and not self.is_interface:
            decl.append('abstract')
        if cf.access & ACC_FINAL:
            decl.append('final')
        kind = 'interface' if self.is_interface else 'class'
        extends = ''
        if self.is_interface:
            if cf.interfaces:
                extends = ' extends ' + ', '.join(i.replace('/', '.') for i in cf.interfaces)
        else:
            if cf.super and cf.super != 'java/lang/Object':
                extends = ' extends ' + cf.super.replace('/', '.')
            if cf.interfaces:
                extends += ' implements ' + ', '.join(i.replace('/', '.') for i in cf.interfaces)
        lines.append('%s %s %s%s {' % (' '.join(decl), kind, self.simple, extends))
        fields, finals_without_init = self.field_lines()
        lines.extend(fields)
        extra = self.tail_extra()
        if extra:
            lines.append('')
            lines.extend(extra)
        methods = self.method_lines(finals_without_init)
        if methods:
            lines.append('')
            lines.extend(methods)
        if self.name == 'org/lwjgl/opengl/Display':
            lines.extend([''] + DISPLAY_EXTRA_METHODS)
        lines.append('}')
        return '\n'.join(lines) + '\n'


def default_field_value(ftype):
    if ftype == 'boolean':
        return 'false'
    if ftype in ('byte', 'short', 'char', 'int'):
        return '0'
    if ftype == 'long':
        return '0L'
    if ftype == 'float':
        return '0.0f'
    if ftype == 'double':
        return '0.0d'
    return 'null'


def load_classes(paths):
    classes = {}
    for path in paths:
        zf = zipfile.ZipFile(path)
        for entry in zf.namelist():
            if not entry.endswith('.class'):
                continue
            name = entry[:-6]
            classes[name] = ClassFile(zf.read(entry))
    return classes


def keep(name):
    return any(name == p or name.startswith(p) for p in KEEP_PREFIXES)


def closure(classes, seeds):
    todo = [s for s in seeds if s in classes]
    seen = set()
    out = []
    while todo:
        name = todo.pop()
        if name in seen:
            continue
        seen.add(name)
        cf = classes.get(name)
        if cf is None or keep(name):
            continue
        out.append(name)
        related = []
        if cf.super:
            related.append(cf.super)
        related.extend(cf.interfaces)
        for _, _, desc, _ in cf.fields:
            t, _ = parse_type(desc, 0)
            related.append(t)
        for _, _, desc, _ in cf.methods:
            params, ret = parse_method(desc)
            related.extend(params)
            related.append(ret)
        for t in related:
            base = t.replace('[]', '')
            while base.endswith('[]'):
                base = base[:-2]
            if base.startswith('org.lwjgl.') and base.replace('.', '/') in classes:
                todo.append(base.replace('.', '/'))
    return sorted(out)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--lwjgl', default=None, help='lwjgl.jar path')
    ap.add_argument('--lwjgl-util', default=None, help='lwjgl_util.jar path')
    ap.add_argument('--out', default='src/headless/java')
    ap.add_argument('--list', action='store_true')
    args = ap.parse_args(argv)

    lwjgl = args.lwjgl or os.environ.get('LWJGL_JAR') or 'C:/@/Starsector/starsector-core/lwjgl.jar'
    lwjgl_util = args.lwjgl_util or os.environ.get('LWJGL_UTIL_JAR') or 'C:/@/Starsector/starsector-core/lwjgl_util.jar'
    classes = load_classes([lwjgl, lwjgl_util])
    CLASSES.update(classes)
    names = closure(classes, SEEDS)
    if args.list:
        for n in names:
            print(n)
        return 0
    written = 0
    for name in names:
        src = Emitter(classes[name]).emit()
        path = os.path.join(args.out, name + '.java')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(src)
        written += 1
    print('generated %d stub classes into %s' % (written, args.out))
    return 0


if __name__ == '__main__':
    sys.exit(main())
