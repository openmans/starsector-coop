package coop.save;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import coop.fleet.CoopLocations;
import coop.util.CoopLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Restores a guest's lost local save from a host-embedded {@link CoopGuestSnapshot}
 * ({@code coop.guestFleetSnapshots} / {@code coop.guestFleetSnapshot}).
 *
 * <p>Replaces the guest's fresh-start credits, cargo stacks, fleet ships (variant, name, hull/CR,
 * mothballed flag), and officer roster atomically behind a pure {@link Target} seam so all logic is
 * unit-tested without an engine.
 */
public final class CoopGuestSnapshotRestorer {

    private CoopGuestSnapshotRestorer() {
    }

    public record RestoreReport(boolean applied, int shipsRestored, int cargoStacksRestored,
                                int officersRestored, double creditsRestored, String locationId) {
        public static RestoreReport skipped() {
            return new RestoreReport(false, 0, 0, 0, 0.0, "");
        }
    }

    /** Engine abstraction so restore behaviour is unit-testable without a running SectorAPI. */
    public interface Target {
        boolean isReady();

        void setCredits(double credits);

        void clearCargo();

        boolean addCargoStack(CoopGuestSnapshot.CargoStack stack);

        void clearFleetAndOfficers();

        boolean addShip(CoopGuestSnapshot.Ship ship);

        boolean addOfficer(CoopGuestSnapshot.Officer officer);

        void moveToLocation(String locationId);
    }

    /** Applies {@code snapshot} onto {@code target}. Never throws on partial failures. */
    public static RestoreReport restore(CoopGuestSnapshot snapshot, Target target) {
        if (snapshot == null || target == null || !target.isReady()) {
            return RestoreReport.skipped();
        }
        target.setCredits(Math.max(0.0, snapshot.getCredits()));
        target.clearCargo();
        int cargoRestored = 0;
        for (CoopGuestSnapshot.CargoStack stack : snapshot.getCargo()) {
            if (stack != null && stack.getQuantity() > 0 && target.addCargoStack(stack)) {
                cargoRestored++;
            }
        }
        int shipsRestored = 0;
        int officersRestored = 0;
        if (!snapshot.getShips().isEmpty()) {
            target.clearFleetAndOfficers();
            for (CoopGuestSnapshot.Ship ship : snapshot.getShips()) {
                if (ship != null && target.addShip(ship)) {
                    shipsRestored++;
                }
            }
            for (CoopGuestSnapshot.Officer officer : snapshot.getOfficers()) {
                if (officer != null && target.addOfficer(officer)) {
                    officersRestored++;
                }
            }
        }
        String loc = snapshot.getLocationId() == null ? "" : snapshot.getLocationId();
        if (!loc.isEmpty()) {
            target.moveToLocation(loc);
        }
        return new RestoreReport(true, shipsRestored, cargoRestored, officersRestored,
                Math.max(0.0, snapshot.getCredits()), loc);
    }

    /** Production engine adapter targeting the local guest's player fleet. */
    public static Target liveTarget(SectorAPI sector) {
        Objects.requireNonNull(sector, "sector");
        return new Target() {
            private CampaignFleetAPI fleet() {
                return sector.getPlayerFleet();
            }

            @Override
            public boolean isReady() {
                return fleet() != null && fleet().getCargo() != null && fleet().getFleetData() != null;
            }

            @Override
            public void setCredits(double credits) {
                fleet().getCargo().getCredits().set((float) credits);
            }

            @Override
            public void clearCargo() {
                CargoAPI cargo = fleet().getCargo();
                cargo.clear();
            }

            @Override
            public boolean addCargoStack(CoopGuestSnapshot.CargoStack stack) {
                try {
                    CargoAPI.CargoItemType type = CargoAPI.CargoItemType.valueOf(stack.getType());
                    fleet().getCargo().addItems(type, stack.getId(), (float) stack.getQuantity());
                    return true;
                } catch (RuntimeException | LinkageError ex) {
                    CoopLog.warn(CoopGuestSnapshotRestorer.class,
                            "Skipped unresolvable cargo stack during restore: " + stack.getId(), ex);
                    return false;
                }
            }

            @Override
            public void clearFleetAndOfficers() {
                FleetDataAPI data = fleet().getFleetData();
                for (FleetMemberAPI m : new ArrayList<>(data.getMembersListCopy())) {
                    data.removeFleetMember(m);
                }
                for (var o : new ArrayList<>(data.getOfficersCopy())) {
                    if (o != null && o.getPerson() != null) {
                        data.removeOfficer(o.getPerson());
                    }
                }
            }

            @Override
            public boolean addShip(CoopGuestSnapshot.Ship ship) {
                try {
                    String variantId = ship.getVariantId().isEmpty()
                            ? ship.getHullId() + "_Hull" : ship.getVariantId();
                    FleetMemberAPI member = Global.getFactory().createFleetMember(
                            FleetMemberType.SHIP, variantId);
                    if (member == null) {
                        return false;
                    }
                    if (!ship.getShipName().isEmpty()) {
                        member.setShipName(ship.getShipName());
                    }
                    if (member.getStatus() != null) {
                        member.getStatus().setHullFraction((float) ship.getHullFraction());
                    }
                    if (member.getRepairTracker() != null) {
                        member.getRepairTracker().setCR(ship.getCr());
                    }
                    fleet().getFleetData().addFleetMember(member);
                    return true;
                } catch (RuntimeException | LinkageError ex) {
                    CoopLog.warn(CoopGuestSnapshotRestorer.class,
                            "Skipped unresolvable ship during restore: " + ship.getHullId(), ex);
                    return false;
                }
            }

            @Override
            public boolean addOfficer(CoopGuestSnapshot.Officer officer) {
                try {
                    PersonAPI person = Global.getFactory().createPerson();
                    if (!officer.getName().isEmpty()) {
                        String[] parts = officer.getName().trim().split("\\s+", 2);
                        person.getName().setFirst(parts[0]);
                        person.getName().setLast(parts.length > 1 ? parts[1] : "");
                    }
                    if (!officer.getPersonality().isEmpty()) {
                        person.setPersonality(officer.getPersonality());
                    }
                    if (person.getStats() != null && officer.getLevel() > 0) {
                        person.getStats().setLevel(officer.getLevel());
                    }
                    fleet().getFleetData().addOfficer(person);
                    return true;
                } catch (RuntimeException | LinkageError ex) {
                    return false;
                }
            }

            @Override
            public void moveToLocation(String locationId) {
                try {
                    LocationAPI targetLoc = CoopLocations.byId(sector, locationId);
                    CampaignFleetAPI f = fleet();
                    if (targetLoc != null && f != null && f.getContainingLocation() != targetLoc) {
                        if (f.getContainingLocation() != null) {
                            f.getContainingLocation().removeEntity(f);
                        }
                        targetLoc.addEntity(f);
                    }
                } catch (RuntimeException | LinkageError ignored) {
                    // best-effort relocation
                }
            }
        };
    }
}
