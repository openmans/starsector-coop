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
import coop.campaign.CoopBountyAuthority.PersonBounty;
import coop.util.CoopIntelFacts;
import coop.util.CoopLog;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Phase 34: guest-side mirrored person bounty intel. Holds primitives and strings only so XStream
 * save serialization stays stable across updates, and self-expires after {@link #STALE_DAYS} without
 * a session refresh.
 */
public class CoopPersonBountyIntel extends BaseIntelPlugin implements CoopIntelFacts {

    public static final float STALE_DAYS = 10f;

    private String bountyId;
    private String coopFleetId;
    private String personName;
    private String portraitId;
    private String factionId;
    private int level;
    private int credits;
    private String hideoutSystemId;
    private String hideoutName;
    private int daysRemaining;
    private String stage;
    private long lastTouchedTimestamp;

    public CoopPersonBountyIntel(PersonBounty bounty) {
        assign(bounty);
        SectorAPI sector = Global.getSector();
        if (sector != null) {
            sector.addScript(this);
        }
    }

    public final void update(PersonBounty bounty) {
        if (bounty != null) {
            assign(bounty);
        }
    }

    public final void touch() {
        lastTouchedTimestamp = clockTimestamp();
    }

    private void assign(PersonBounty bounty) {
        if (bounty == null) {
            return;
        }
        this.bountyId = bounty.bountyId();
        this.coopFleetId = bounty.coopFleetId();
        this.personName = bounty.personName();
        this.portraitId = bounty.portraitId();
        this.factionId = bounty.factionId();
        this.level = bounty.level();
        this.credits = bounty.credits();
        this.hideoutSystemId = bounty.hideoutSystemId();
        this.hideoutName = bounty.hideoutName();
        this.daysRemaining = bounty.daysRemaining();
        this.stage = bounty.stage();
        this.lastTouchedTimestamp = clockTimestamp();
    }

    public String bountyId() {
        return bountyId == null ? "" : bountyId;
    }

    public PersonBounty toRecord() {
        return new PersonBounty(bountyId(), nonNull(coopFleetId), nonNull(personName),
                nonNull(portraitId), nonNull(factionId), level, credits,
                nonNull(hideoutSystemId), nonNull(hideoutName), daysRemaining, nonNull(stage));
    }

    @Override
    public Map<String, Object> intelFacts() {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("kind", "PERSON_BOUNTY");
        facts.put("bountyId", bountyId());
        facts.put("coopFleetId", nonNull(coopFleetId));
        facts.put("personName", nonNull(personName));
        facts.put("factionId", nonNull(factionId));
        facts.put("level", level);
        facts.put("credits", credits);
        facts.put("hideoutSystemId", nonNull(hideoutSystemId));
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
            CoopLog.warn(CoopPersonBountyIntel.class, "Failed to advance coop person bounty intel", ex);
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
        String target = nonNull(personName);
        return target.isEmpty() ? "Personal Bounty" : "Bounty: " + target;
    }

    @Override
    public String getSmallDescriptionTitle() {
        return getName();
    }

    @Override
    public String getSortString() {
        return "Bounty";
    }

    @Override
    public String getIcon() {
        if (portraitId != null && !portraitId.isEmpty()) {
            return portraitId;
        }
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
            if (sector == null || hideoutSystemId == null || hideoutSystemId.isEmpty()) {
                return null;
            }
            StarSystemAPI system = sector.getStarSystem(hideoutSystemId);
            if (system != null) {
                return system.getCenter();
            }
            return sector.getEntityById(hideoutSystemId);
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
        info.addPara("Reward: %s", 0f, tc, h, Misc.getDGSCredits(credits));
        if (hideoutName != null && !hideoutName.isEmpty()) {
            info.addPara("Location: %s", 0f, tc, h, hideoutName);
        }
        info.addPara("Time remaining: %s days", 0f, tc, h, String.valueOf(Math.max(0, daysRemaining)));
        unindent(info);
    }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        Color h = Misc.getHighlightColor();
        String factionName = factionDisplayName();
        String where = nonNull(hideoutName).isEmpty() ? "an undisclosed system" : hideoutName;
        info.addPara("%s has posted a bounty of %s for the defeat of %s, last reported near %s.",
                10f, h,
                factionName.isEmpty() ? "A local authority" : factionName,
                Misc.getDGSCredits(credits),
                nonNull(personName).isEmpty() ? "a wanted commander" : personName,
                where);
        info.addPara("Time remaining: %s days. Defeating the target fleet awards the full bounty.",
                10f, h, String.valueOf(Math.max(0, daysRemaining)));
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
