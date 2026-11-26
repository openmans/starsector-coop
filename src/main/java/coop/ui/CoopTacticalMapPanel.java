package coop.ui;

import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import coop.combat.CoopBattleOrder;
import coop.combat.CoopBattleStatus;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Phase 22: GL-drawn interactive tactical map panel for the non-fighting player.
 *
 * <p>Renders the fighting side's fog-filtered ships and objectives at 5 Hz, supports pan (drag)
 * and zoom (wheel), multi-select of the partner's own allied ships, right-click tactical orders
 * ({@code ENGAGE}, {@code ESCORT}, {@code CAPTURE}, {@code DEFEND}, {@code WAYPOINT}), and
 * keyboard shortcuts ({@code R} retreat, {@code X} cancel, {@code ESC} close).
 *
 * <p>All coordinate projection and hit-testing live in {@link Viewport} so they are unit-tested
 * headless without OpenGL.
 */
public final class CoopTacticalMapPanel implements CustomUIPanelPlugin {

    public static final float MIN_ZOOM = 0.015f;
    public static final float MAX_ZOOM = 0.25f;
    public static final float HIT_RADIUS_PX = 18f;

    /** Pure world <-> screen projection math and hit-testing (headless-testable). */
    public static final class Viewport {
        private float centerX;
        private float centerY;
        private float zoom = 0.055f;
        private float panelX;
        private float panelY;
        private float panelWidth = 900f;
        private float panelHeight = 620f;

        public void setBounds(float x, float y, float width, float height) {
            this.panelX = x;
            this.panelY = y;
            this.panelWidth = Math.max(1f, width);
            this.panelHeight = Math.max(1f, height);
        }

        public void fitMap(float mapWidth, float mapHeight) {
            float safeW = Math.max(1000f, mapWidth);
            float safeH = Math.max(1000f, mapHeight);
            float fit = Math.min((panelWidth * 0.85f) / safeW, (panelHeight * 0.85f) / safeH);
            this.zoom = clampZoom(fit);
            this.centerX = 0f;
            this.centerY = 0f;
        }

        public void panByScreenDelta(float dxPx, float dyPx) {
            centerX -= dxPx / zoom;
            centerY -= dyPx / zoom;
        }

        public void zoomByWheel(int wheelDelta) {
            if (wheelDelta == 0) {
                return;
            }
            float factor = wheelDelta > 0 ? 1.15f : (1f / 1.15f);
            zoom = clampZoom(zoom * factor);
        }

        public float worldToScreenX(float worldX) {
            return panelX + panelWidth * 0.5f + (worldX - centerX) * zoom;
        }

        public float worldToScreenY(float worldY) {
            return panelY + panelHeight * 0.5f + (worldY - centerY) * zoom;
        }

        public float screenToWorldX(float screenX) {
            return centerX + (screenX - (panelX + panelWidth * 0.5f)) / zoom;
        }

        public float screenToWorldY(float screenY) {
            return centerY + (screenY - (panelY + panelHeight * 0.5f)) / zoom;
        }

        public float zoom() { return zoom; }
        public float centerX() { return centerX; }
        public float centerY() { return centerY; }

        /** Picks the closest ship within {@link #HIT_RADIUS_PX} screen pixels, or null. */
        public CoopBattleStatus.ShipRecord pickShip(List<CoopBattleStatus.ShipRecord> ships,
                                                    float screenX, float screenY) {
            if (ships == null || ships.isEmpty()) {
                return null;
            }
            CoopBattleStatus.ShipRecord best = null;
            float bestDistSq = HIT_RADIUS_PX * HIT_RADIUS_PX;
            for (CoopBattleStatus.ShipRecord ship : ships) {
                float sx = worldToScreenX(ship.x());
                float sy = worldToScreenY(ship.y());
                float dx = sx - screenX;
                float dy = sy - screenY;
                float d2 = dx * dx + dy * dy;
                if (d2 <= bestDistSq) {
                    bestDistSq = d2;
                    best = ship;
                }
            }
            return best;
        }

        /** Picks the closest objective within {@link #HIT_RADIUS_PX} screen pixels, or null. */
        public CoopBattleStatus.ObjectiveRecord pickObjective(
                List<CoopBattleStatus.ObjectiveRecord> objectives, float screenX, float screenY) {
            if (objectives == null || objectives.isEmpty()) {
                return null;
            }
            CoopBattleStatus.ObjectiveRecord best = null;
            float bestDistSq = HIT_RADIUS_PX * HIT_RADIUS_PX;
            for (CoopBattleStatus.ObjectiveRecord obj : objectives) {
                float sx = worldToScreenX(obj.x());
                float sy = worldToScreenY(obj.y());
                float dx = sx - screenX;
                float dy = sy - screenY;
                float d2 = dx * dx + dy * dy;
                if (d2 <= bestDistSq) {
                    bestDistSq = d2;
                    best = obj;
                }
            }
            return best;
        }

        private static float clampZoom(float z) {
            return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
        }
    }

    private final Supplier<CoopBattleStatus> statusSupplier;
    private final Consumer<CoopBattleOrder.Order> orderSender;
    private final Runnable onDismiss;
    private final Viewport viewport = new Viewport();
    private final Set<String> selectedShipIds = new LinkedHashSet<>();
    private final Map<String, String> pendingOrderFeedback = new LinkedHashMap<>();
    private PositionAPI position;
    private CoopBitmapFont font;
    private boolean fontLoadAttempted;
    private boolean fittedOnce;
    private boolean dragging;
    private float lastMouseX;
    private float lastMouseY;
    private long orderSeq;
    private String lastNotice = "Left-click your allied ships to select | Right-click target/space to order | R: Retreat | X: Cancel | ESC: Close";

    public CoopTacticalMapPanel(Supplier<CoopBattleStatus> statusSupplier,
                                Consumer<CoopBattleOrder.Order> orderSender,
                                Runnable onDismiss) {
        this.statusSupplier = Objects.requireNonNull(statusSupplier, "statusSupplier");
        this.orderSender = Objects.requireNonNull(orderSender, "orderSender");
        this.onDismiss = Objects.requireNonNull(onDismiss, "onDismiss");
    }

    public Viewport viewport() {
        return viewport;
    }

    public Set<String> selectedShipIds() {
        return Set.copyOf(selectedShipIds);
    }

    public void onOrderAck(CoopBattleOrder.Ack ack) {
        if (ack == null) {
            return;
        }
        if (ack.accepted()) {
            pendingOrderFeedback.put(ack.shipId(), ack.activeAssignment());
            lastNotice = "Order confirmed: " + ack.activeAssignment();
        } else {
            pendingOrderFeedback.remove(ack.shipId());
            lastNotice = "Order rejected: " + ack.reason();
        }
    }

    @Override
    public void positionChanged(PositionAPI position) {
        this.position = position;
        if (position != null) {
            viewport.setBounds(position.getX(), position.getY(), position.getWidth(), position.getHeight());
        }
    }

    @Override
    public void renderBelow(float alphaMult) {
        if (position == null) {
            return;
        }
        float x = position.getX();
        float y = position.getY();
        float w = position.getWidth();
        float h = position.getHeight();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(0.03f, 0.05f, 0.08f, 0.92f * alphaMult);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }

    @Override
    public void render(float alphaMult) {
        if (position == null) {
            return;
        }
        CoopBattleStatus status = statusSupplier.get();
        if (status != null && !fittedOnce) {
            viewport.fitMap(status.bounds().width(), status.bounds().height());
            fittedOnce = true;
        }
        float x = position.getX();
        float y = position.getY();
        float w = position.getWidth();
        float h = position.getHeight();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        if (status != null) {
            drawMapBounds(status.bounds(), alphaMult);
            for (CoopBattleStatus.ObjectiveRecord obj : status.objectives()) {
                drawObjective(obj, alphaMult);
            }
            for (CoopBattleStatus.ShipRecord ship : status.ships()) {
                drawShip(ship, alphaMult);
            }
        }
        // Border
        GL11.glColor4f(0.35f, 0.72f, 0.92f, 0.75f * alphaMult);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x + 1, y + 1);
        GL11.glVertex2f(x + w - 1, y + 1);
        GL11.glVertex2f(x + w - 1, y + h - 1);
        GL11.glVertex2f(x + 1, y + h - 1);
        GL11.glEnd();

        // HUD status line inside panel using mod's own bitmap font
        CoopBitmapFont labelFont = resolveFont();
        if (labelFont != null) {
            String header = status == null
                    ? "Tactical Map — waiting for battle status..."
                    : "Tactical Map (" + status.ownShips().size() + " friendly, "
                            + status.enemyShips().size() + " hostile)"
                            + (status.bounds().paused() ? " [PAUSED]" : "");
            labelFont.draw(header, x + 12f, y + h - 12f, Color.CYAN);
            labelFont.draw(lastNotice, x + 12f, y + 24f, Color.LIGHT_GRAY);
        }
    }

    private CoopBitmapFont resolveFont() {
        if (!fontLoadAttempted) {
            fontLoadAttempted = true;
            try {
                font = CoopBitmapFont.load("graphics/fonts/insignia15LTaa.fnt");
            } catch (Exception | LinkageError ignored) {
                font = null;
            }
        }
        return font;
    }

    @Override
    public void advance(float amount) {
    }

    @Override
    public void processInput(List<InputEventAPI> events) {
        if (events == null || position == null) {
            return;
        }
        CoopBattleStatus status = statusSupplier.get();
        for (InputEventAPI event : events) {
            if (event.isConsumed()) {
                continue;
            }
            if (event.isKeyDownEvent()) {
                int key = event.getEventValue();
                if (key == Keyboard.KEY_ESCAPE) {
                    event.consume();
                    onDismiss.run();
                    return;
                }
                if (key == Keyboard.KEY_R && status != null) {
                    issueOrderToSelected(status, CoopBattleOrder.OrderKind.RETREAT, "", 0f, 0f);
                    event.consume();
                    continue;
                }
                if (key == Keyboard.KEY_X && status != null) {
                    issueOrderToSelected(status, CoopBattleOrder.OrderKind.CANCEL, "", 0f, 0f);
                    event.consume();
                    continue;
                }
            }
            if (!position.containsEvent(event)) {
                if (event.isMouseUpEvent()) {
                    dragging = false;
                }
                continue;
            }
            if (event.isMouseScrollEvent()) {
                viewport.zoomByWheel(event.getEventValue());
                event.consume();
            } else if (event.isLMBDownEvent()) {
                float mx = event.getX();
                float my = event.getY();
                lastMouseX = mx;
                lastMouseY = my;
                CoopBattleStatus.ShipRecord hit = status == null ? null
                        : viewport.pickShip(status.alliedShips(), mx, my);
                if (hit != null) {
                    if (!event.isShiftDown()) {
                        selectedShipIds.clear();
                    }
                    selectedShipIds.add(hit.shipId());
                    lastNotice = "Selected " + hit.name()
                            + (hit.assignment().isEmpty() ? "" : " [" + hit.assignment() + "]");
                } else {
                    dragging = true;
                }
                event.consume();
            } else if (event.isLMBUpEvent()) {
                dragging = false;
                event.consume();
            } else if (event.isMouseMoveEvent() && dragging) {
                float mx = event.getX();
                float my = event.getY();
                viewport.panByScreenDelta(mx - lastMouseX, my - lastMouseY);
                lastMouseX = mx;
                lastMouseY = my;
                event.consume();
            } else if (event.isRMBDownEvent() && status != null && !selectedShipIds.isEmpty()) {
                float mx = event.getX();
                float my = event.getY();
                handleRightClickOrder(status, mx, my);
                event.consume();
            }
        }
    }

    @Override
    public void buttonPressed(Object buttonId) {
    }

    private void handleRightClickOrder(CoopBattleStatus status, float screenX, float screenY) {
        CoopBattleStatus.ShipRecord enemy = viewport.pickShip(status.enemyShips(), screenX, screenY);
        if (enemy != null) {
            issueOrderToSelected(status, CoopBattleOrder.OrderKind.ENGAGE, enemy.shipId(), enemy.x(), enemy.y());
            return;
        }
        CoopBattleStatus.ShipRecord friendly = viewport.pickShip(status.ownShips(), screenX, screenY);
        if (friendly != null) {
            issueOrderToSelected(status, CoopBattleOrder.OrderKind.ESCORT, friendly.shipId(), friendly.x(), friendly.y());
            return;
        }
        CoopBattleStatus.ObjectiveRecord obj = viewport.pickObjective(status.objectives(), screenX, screenY);
        if (obj != null) {
            CoopBattleOrder.OrderKind kind = obj.owner() == 0
                    ? CoopBattleOrder.OrderKind.DEFEND : CoopBattleOrder.OrderKind.CAPTURE;
            issueOrderToSelected(status, kind, obj.id(), obj.x(), obj.y());
            return;
        }
        float wx = viewport.screenToWorldX(screenX);
        float wy = viewport.screenToWorldY(screenY);
        issueOrderToSelected(status, CoopBattleOrder.OrderKind.WAYPOINT, "", wx, wy);
    }

    private void issueOrderToSelected(CoopBattleStatus status, CoopBattleOrder.OrderKind kind,
                                      String targetId, float targetX, float targetY) {
        if (selectedShipIds.isEmpty()) {
            lastNotice = "Select one of your allied ships first.";
            return;
        }
        for (String shipId : selectedShipIds) {
            String orderId = "ord-" + (++orderSeq);
            orderSender.accept(new CoopBattleOrder.Order(
                    orderId, status.battleId(), shipId, kind, targetId, targetX, targetY, false));
            pendingOrderFeedback.put(shipId, kind.name() + "...");
        }
        lastNotice = "Sent " + kind.name() + " to " + selectedShipIds.size() + " ship(s).";
    }

    private void drawMapBounds(CoopBattleStatus.MapBounds bounds, float alpha) {
        float hw = bounds.width() * 0.5f;
        float hh = bounds.height() * 0.5f;
        float x0 = viewport.worldToScreenX(-hw);
        float y0 = viewport.worldToScreenY(-hh);
        float x1 = viewport.worldToScreenX(hw);
        float y1 = viewport.worldToScreenY(hh);
        GL11.glColor4f(0.25f, 0.45f, 0.65f, 0.4f * alpha);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x0, y0);
        GL11.glVertex2f(x1, y0);
        GL11.glVertex2f(x1, y1);
        GL11.glVertex2f(x0, y1);
        GL11.glEnd();
    }

    private void drawObjective(CoopBattleStatus.ObjectiveRecord obj, float alpha) {
        float sx = viewport.worldToScreenX(obj.x());
        float sy = viewport.worldToScreenY(obj.y());
        if (obj.owner() == 0) {
            GL11.glColor4f(0.3f, 0.9f, 0.4f, 0.85f * alpha);
        } else if (obj.owner() == 1) {
            GL11.glColor4f(0.95f, 0.3f, 0.3f, 0.85f * alpha);
        } else {
            GL11.glColor4f(0.85f, 0.85f, 0.85f, 0.75f * alpha);
        }
        float r = 7f;
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(sx, sy + r);
        GL11.glVertex2f(sx + r, sy);
        GL11.glVertex2f(sx, sy - r);
        GL11.glVertex2f(sx - r, sy);
        GL11.glEnd();
    }

    private void drawShip(CoopBattleStatus.ShipRecord ship, float alpha) {
        float sx = viewport.worldToScreenX(ship.x());
        float sy = viewport.worldToScreenY(ship.y());
        double rad = Math.toRadians(ship.facing());
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        float r = ship.ally() ? 8f : 7f;
        if (ship.enemy()) {
            GL11.glColor4f(0.95f, 0.30f, 0.25f, 0.9f * alpha);
        } else if (ship.ally()) {
            GL11.glColor4f(0.95f, 0.82f, 0.22f, 0.95f * alpha);
        } else {
            GL11.glColor4f(0.32f, 0.85f, 0.45f, 0.9f * alpha);
        }
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2f(sx + cos * r, sy + sin * r);
        GL11.glVertex2f(sx - cos * r * 0.7f - sin * r * 0.6f, sy - sin * r * 0.7f + cos * r * 0.6f);
        GL11.glVertex2f(sx - cos * r * 0.7f + sin * r * 0.6f, sy - sin * r * 0.7f - cos * r * 0.6f);
        GL11.glEnd();
        if (selectedShipIds.contains(ship.shipId())) {
            GL11.glColor4f(1f, 1f, 1f, 0.95f * alpha);
            float sr = r + 4f;
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(sx - sr, sy - sr);
            GL11.glVertex2f(sx + sr, sy - sr);
            GL11.glVertex2f(sx + sr, sy + sr);
            GL11.glVertex2f(sx - sr, sy + sr);
            GL11.glEnd();
        }
    }
}
