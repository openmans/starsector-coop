package coop.headless.lwjgl;

import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared state for the Null-LWJGL shim: object-name counters and the answers to the handful of
 * {@code glGet*} queries the engine really makes.
 *
 * <p>Generated stubs call into here so that every GL class shares one id space. Names must stay
 * unique-per-kind only loosely: the engine stores texture ids, list ids and buffer ids in separate
 * fields and never compares ids across kinds, so one counter is enough and avoids bookkeeping.
 */
public final class NullGL {

    private static final AtomicInteger IDS = new AtomicInteger(1);

    /**
     * Reported extension string. Deliberately does not advertise GL_NVX_gpu_memory_info: the engine
     * then takes its ATI VRAM query path, which {@link #getInteger(int)} answers with a large value
     * instead of the NVX path's zero.
     */
    private static final String EXTENSIONS = "GL_ARB_multitexture GL_ARB_texture_env_add "
            + "GL_ARB_texture_env_combine GL_ARB_texture_env_dot3 GL_ARB_multisample "
            + "GL_ARB_vertex_buffer_object GL_ARB_texture_non_power_of_two "
            + "GL_ARB_framebuffer_object GL_EXT_framebuffer_object GL_EXT_framebuffer_blit "
            + "GL_EXT_blend_func_separate GL_EXT_blend_minmax GL_EXT_blend_subtract "
            + "GL_EXT_texture_filter_anisotropic GL_EXT_texture_compression_s3tc "
            + "GL_SGIS_generate_mipmap GL_EXT_draw_range_elements";

    private NullGL() {
    }

    /** Next free object name; 0 is never handed out, so "allocation failed" checks pass. */
    public static int nextId() {
        return IDS.getAndIncrement();
    }

    /**
     * Fills an id buffer the way {@code glGenTextures(int, IntBuffer)} does: writes from the current
     * position, leaves the position where the caller left it (engine code reads index 0 afterwards).
     */
    public static void fillIds(IntBuffer buffer) {
        if (buffer == null) {
            return;
        }
        int pos = buffer.position();
        for (int i = pos; i < buffer.limit(); i++) {
            buffer.put(i, IDS.getAndIncrement());
        }
        buffer.position(pos);
    }

    /** Single-value {@code glGetInteger}. Unknown names answer 1 rather than 0: 0 as a limit reads
     * as "unsupported", and several engine paths divide by or clamp against that value. */
    public static int getInteger(int pname) {
        switch (pname) {
            case 0x0D33: // GL_MAX_TEXTURE_SIZE
            case 0x0D3A: // GL_MAX_VIEWPORT_DIMS
                return 16384;
            case 0x84E2: // GL_MAX_TEXTURE_UNITS
                return 4;
            case 0x8869: // GL_MAX_VERTEX_ATTRIBS
                return 16;
            case 0x8872: // GL_MAX_TEXTURE_IMAGE_UNITS
            case 0x8B4C: // GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS
            case 0x8B4D: // GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS
                return 32;
            case 0x0D31: // GL_MAX_LIGHTS
                return 8;
            case 0x0D36: // GL_MAX_MODELVIEW_STACK_DEPTH
            case 0x0D38: // GL_MAX_PROJECTION_STACK_DEPTH
                return 32;
            case 0x0D57: // GL_STENCIL_BITS
                return 8;
            case 0x0D52: // GL_RED_BITS
            case 0x0D53: // GL_GREEN_BITS
            case 0x0D54: // GL_BLUE_BITS
            case 0x0D55: // GL_ALPHA_BITS
                return 8;
            case 0x8073: // GL_MAX_3D_TEXTURE_SIZE
                return 2048;
            case 0x88FF: // GL_MAX_ARRAY_TEXTURE_LAYERS
                return 2048;
            case 0x851C: // GL_MAX_CUBE_MAP_TEXTURE_SIZE
                return 16384;
            case 0x84FD: // GL_MAX_TEXTURE_LOD_BIAS (float in real GL; harmless)
                return 15;
            case 0x0D3B: // GL_MAX_ATTRIB_STACK_DEPTH
            case 0x0D39: // GL_MAX_TEXTURE_STACK_DEPTH
                return 16;
            case 0x86A2: // GL_NUM_COMPRESSED_TEXTURE_FORMATS
                return 0;
            case 0x87FD: // GL_TEXTURE_FREE_MEMORY_ATI (KB)
            case 0x9049: // GL_GPU_MEM_INFO_CURRENT_AVAILABLE_MEM_NVX (KB)
                return 2 * 1024 * 1024;
            default:
                return 1;
        }
    }

    /** Multi-value {@code glGetInteger}. */
    public static void getInteger(int pname, IntBuffer buffer) {
        if (buffer == null) {
            return;
        }
        int pos = buffer.position();
        if (pname == 0x0BA2) { // GL_VIEWPORT
            put(buffer, pos, 0, 0, 1024, 768);
            buffer.position(pos);
            return;
        }
        for (int i = pos; i < buffer.limit(); i++) {
            buffer.put(i, getInteger(pname));
        }
        buffer.position(pos);
    }

    private static void put(IntBuffer buffer, int pos, int... values) {
        for (int i = 0; i < values.length && pos + i < buffer.limit(); i++) {
            buffer.put(pos + i, values[i]);
        }
    }

    public static String getString(int pname) {
        switch (pname) {
            case 0x1F00: // GL_VENDOR
                return "Headless";
            case 0x1F01: // GL_RENDERER
                return "Headless NullGL";
            case 0x1F02: // GL_VERSION
                return "2.1 Headless";
            case 0x1F03: // GL_EXTENSIONS
                return EXTENSIONS;
            case 0x8B8C: // GL_SHADING_LANGUAGE_VERSION
                return "1.20";
            default:
                return null;
        }
    }
}
