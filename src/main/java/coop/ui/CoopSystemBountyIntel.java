package coop.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import coop.campaign.CoopBountyAuthority.SystemBounty;
import coop.util.CoopIntelFacts;
import coop.util.CoopLog;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Phase 34: guest-side mirrored system bounty intel. Replaces the suppressed local
 * {@code SystemBountyManager} so both players see the exact same system bounties.
 */
public class CoopSystemBountyIntel extends BaseIntelPlugin implements CoopIntelFacts {

    public static final float STALE_DAYS = 10f;

    private String bountyId;
    private String systemId;
    private String systemName;
    private String marketId;
    private String factionId;
    private int creditsPerFp;
    private int daysRemaining;
    private long lastTouchedTimestamp;

    public CoopSystemBountyIntel(SystemBounty bounty) {
        assign(bounty);
        SectorAPI sector = Global.getSector();
        if (sector != null) {
            sector.addScript(this);
        }
    }

    public final void update(SystemBounty bounty) {
        if (bounty != null) {
            assign(bounty);
        }
    }

    public final void touch() {
        lastTouchedTimestamp = clockTimestamp();
    }

    private void assign(SystemBounty bounty) {
        if (bounty == null) {
            return;
        }
        this.bountyId = bounty.bountyId();
        this.systemId = bounty.systemId();
        this.systemName = bounty.systemName();
        this.marketId = bounty.marketId();
        this.factionId = bounty.factionId();
        this.creditsPerFp = bounty.creditsPerFp();
        this.daysRemaining = bounty.daysRemaining();
        this.lastTouchedTimestamp = clockTimestamp();
    }

    public String bountyId() {
        return bountyId == null ? "" : bountyId;
    }

    public SystemBounty toRecord() {
        return new SystemBounty(bountyId(), nonNull(systemId), nonNull(systemName),
                nonNull(marketId), nonNull(factionId), creditsPerFp, daysRemaining);
    }

    @Override
    public Map<String, Object> intelFacts() {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("kind", "SYSTEM_BOUNTY");
        facts.put("bountyId", bountyId());
        facts.put("systemId", nonNull(systemId));
        facts.put("marketId", nonNull(marketId));
        facts.put("factionId", nonNull(factionId));
        facts.put("creditsPerFp", creditsPerFp);
        facts.put("daysRemaining", daysRemaining);
        return facts;
    }

    @Override
    protected void advanceImpl(float amount) {
        if (isEnding() || isEnded()) {
            return;
        }
        try {
            if (lastTouchedTimestamp == 0L) {
                lastTouchedTimestamp = clockTimestamp();
                return;
            }
            SectorAPI sector = Global.getSector();
            if (sector != null && sector.getClock() != null
                    && sector.getClock().getElapsedDaysSince(lastTouchedTimestamp) >= STALE_DAYS) {
                endImmediately();
            }
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(CoopSystemBountyIntel.class, "Failed to advance coop system bounty intel", ex);
            endImmediately();
        }
    }

    @Override
    protected void notifyEnded() {
        SectorAPI sector = Global.getSector();
        if (sector != null) {
            sector.removeScript(this);
        }
    }

    @Override
    public String getName() {
        String sys = nonNull(systemName);
        return sys.isEmpty() ? "System Bounty" : "System Bounty: " + sys;
    }

    @Override
    public String getSmallDescriptionTitle() {
        return getName();
    }

    @Override
    public String getSortString() {
        return "System Bounty";
    }

    @Override
    public String getIcon() {
        FactionAPI faction = faction();
        return faction != null ? faction.getCrest() : null;
    }

    @Override
    public FactionAPI getFactionForUIColors() {
        return faction();
    }

    @Override
    public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> tags = super.getIntelTags(map);
        tags.add(Tags.INTEL_BOUNTY);
        return tags;
    }

    @Override
    public SectorEntityToken getMapLocation(SectorMapAPI map) {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null || systemId == null || systemId.isEmpty()) {
                return null;
            }
            StarSystemAPI system = sector.getStarSystem(systemId);
            return system != null ? system.getCenter() : sector.getEntityById(systemId);
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    @Override
    protected void addBulletPoints(TooltipMakerAPI info, ListInfoMode mode) {
        Color tc = getBulletColorForMode(mode);
        Color h = Misc.getHighlightColor();
        bullet(info);
        String factionName = factionDisplayName();
        if (!factionName.isEmpty()) {
            info.addPara("Faction: %s", 0f, tc, factionColor(tc), factionName);
        }
        info.addPara("Reward: %s per frigate-equivalent", 0f, tc, h, Misc.getDGSCredits(creditsPerFp));
        info.addPara("Time remaining: %s days", 0f, tc, h, String.valueOf(Math.max(0, daysRemaining)));
        unindent(info);
    }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        Color h = Misc.getHighlightColor();
        String factionName = factionDisplayName();
        String sys = nonNull(systemName).isEmpty() ? "the system" : systemName;
        info.addPara("%s has posted a standing system bounty in %s, paying %s per fleet point of hostile ships destroyed.",
                10f, h,
                factionName.isEmpty() ? "The local faction" : factionName,
                sys,
                Misc.getDGSCredits(creditsPerFp));
        info.addPara("Time remaining: %s days.", 10f, h, String.valueOf(Math.max(0, daysRemaining)));
    }

    private FactionAPI faction() {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null || factionId == null || factionId.isEmpty()) {
                return null;
            }
            return sector.getFaction(factionId);
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private String factionDisplayName() {
        FactionAPI f = faction();
        return f == null ? nonNull(factionId) : f.getDisplayName();
    }

    private Color factionColor(Color fallback) {
        FactionAPI f = faction();
        return f == null ? fallback : f.getBaseUIColor();
    }

    private static long clockTimestamp() {
        try {
            SectorAPI sector = Global.getSector();
            return sector == null || sector.getClock() == null ? 0L : sector.getClock().getTimestamp();
        } catch (RuntimeException | LinkageError ex) {
            return 0L;
        }
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}
