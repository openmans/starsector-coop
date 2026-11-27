package coop.combat.live;

import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.ViewportAPI;
import coop.combat.live.CoopCombatNetCodec.BeamSample;
import org.lwjgl.opengl.GL11;

import java.util.EnumSet;
import java.util.List;

/**
 * Native OpenGL {@link BaseCombatLayeredRenderingPlugin} that renders synchronized combat beams
 * ({@code COMBAT_BEAMS}) on the joining partner's combat client with accurate additive glow,
 * fringe/core colors, beam width, and impact flare.
 */
public final class CoopCombatBeamRenderer extends BaseCombatLayeredRenderingPlugin {

    private static final EnumSet<CombatEngineLayers> LAYERS =
            EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_LAYER);

    private volatile List<BeamSample> activeBeams = List.of();
    private volatile long beamsUpdatedAtMillis;

    public void setBeams(List<BeamSample> beams, long nowMillis) {
        this.activeBeams = beams == null ? List.of() : List.copyOf(beams);
        this.beamsUpdatedAtMillis = nowMillis;
    }

    public void clear() {
        this.activeBeams = List.of();
    }

    @Override
    public EnumSet<CombatEngineLayers> getActiveLayers() {
        return LAYERS;
    }

    @Override
    public float getRenderRadius() {
        return 100_000f;
    }

    @Override
    public void render(CombatEngineLayers layer, ViewportAPI viewport) {
        if (layer != CombatEngineLayers.ABOVE_SHIPS_LAYER) {
            return;
        }
        List<BeamSample> beams = activeBeams;
        if (beams.isEmpty()) {
            return;
        }
        // Expire stale beams automatically if no update arrived for 250 ms
        if (System.currentTimeMillis() - beamsUpdatedAtMillis > 250L) {
            return;
        }
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_LINE_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);

        for (BeamSample b : beams) {
            float dx = b.toX() - b.fromX();
            float dy = b.toY() - b.fromY();
            float len = (float) Math.hypot(dx, dy);
            if (len < 1f) {
                continue;
            }
            float nx = -dy / len;
            float ny = dx / len;
            float halfFringe = Math.max(2f, b.width() * 0.55f);
            float halfCore = Math.max(1f, b.width() * 0.24f);

            // Fringe quad
            setArgbColor(b.fringeArgb(), 0.72f);
            drawQuad(b.fromX(), b.fromY(), b.toX(), b.toY(), nx * halfFringe, ny * halfFringe);

            // Bright core quad
            setArgbColor(b.coreArgb(), 0.96f);
            drawQuad(b.fromX(), b.fromY(), b.toX(), b.toY(), nx * halfCore, ny * halfCore);

            // Impact glow at endpoint when hitting shield or hull
            if (b.hitting()) {
                float r = Math.max(6f, b.width() * 0.85f);
                setArgbColor(b.fringeArgb(), 0.80f);
                drawImpactDiamond(b.toX(), b.toY(), r);
                setArgbColor(b.coreArgb(), 0.95f);
                drawImpactDiamond(b.toX(), b.toY(), r * 0.48f);
            }
        }
        GL11.glPopAttrib();
    }

    private static void drawQuad(float x1, float y1, float x2, float y2, float ox, float oy) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x1 - ox, y1 - oy);
        GL11.glVertex2f(x1 + ox, y1 + oy);
        GL11.glVertex2f(x2 + ox, y2 + oy);
        GL11.glVertex2f(x2 - ox, y2 - oy);
        GL11.glEnd();
    }

    private static void drawImpactDiamond(float cx, float cy, float r) {
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(cx, cy);
        for (int i = 0; i <= 8; i++) {
            double ang = (Math.PI * 2.0 * i) / 8.0;
            GL11.glVertex2f(cx + (float) Math.cos(ang) * r, cy + (float) Math.sin(ang) * r);
        }
        GL11.glEnd();
    }

    private static void setArgbColor(int argb, float alphaScale) {
        float a = ((argb >>> 24) & 0xFF) / 255f;
        if (a <= 0.01f) {
            a = 1f;
        }
        float r = ((argb >>> 16) & 0xFF) / 255f;
        float g = ((argb >>> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        GL11.glColor4f(r, g, b, Math.min(1f, a * alphaScale));
    }
}
