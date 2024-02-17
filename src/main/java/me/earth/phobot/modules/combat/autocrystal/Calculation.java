package me.earth.phobot.modules.combat.autocrystal;

import lombok.Setter;
import me.earth.phobot.Phobot;
import me.earth.phobot.damagecalc.CrystalPosition;
import me.earth.phobot.damagecalc.DamageCalculator;
import me.earth.phobot.modules.client.anticheat.AntiCheat;
import me.earth.phobot.pathfinder.blocks.BlockPathfinderWithBlacklist;
import me.earth.phobot.services.InvincibilityFrameService;
import me.earth.phobot.util.RetryUtil;
import me.earth.phobot.util.entity.EntityUtil;
import me.earth.phobot.util.mutables.MutPos;
import me.earth.phobot.util.world.BlockStateLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.apache.commons.lang3.mutable.MutableObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Setter
public class Calculation extends BreakCalculation implements Runnable {
    private final MutPos mutPos = new MutPos();
    private final AntiCheat antiCheat;

    private final MutableObject<CrystalPosition> bestPos = new MutableObject<>();
    private final MutableObject<CrystalPosition> bestBlockedByCrystalPos = new MutableObject<>();
    private final MutableObject<CrystalPosition> bestFacePlacePos = new MutableObject<>();
    private final MutableObject<CrystalPosition> bestBlockedByCrystalFacePlacePos = new MutableObject<>();
    private final MutableObject<CrystalPosition> bestObbyPos = new MutableObject<>();
    private final MutableObject<CrystalPosition> bestBlockedByCrystalObbyPos = new MutableObject<>();

    private boolean runBreakingCalculation = true;
    private boolean hasBreakingCalculationRun = false;
    private boolean placed = false;
    private double maxY;
    private double eyeY;

    public Calculation(CrystalPlacingModule module, Minecraft mc, Phobot phobot, LocalPlayer player, ClientLevel level, DamageCalculator calculator) {
        super(module, mc, phobot, player, level, calculator);
        this.antiCheat = phobot.getAntiCheat();
    }

    @Override
    public void run() throws RunningOnDifferentThreadException {
        if (runBreakingCalculation && module.breakTimer().passed(100)/*Don't run poll breaking that much*/) {
            breakCrystals();
        }

        calculatePlacements(true, module.positionPool().getPositions());
    }

    @Override
    public void breakCrystals() {
        super.breakCrystals();
        hasBreakingCalculationRun = true;
    }

    protected void preparePlaceCalculation() {
        maxY = getMaxY();
        eyeY = player.getEyeY();
    }

    protected void calculatePlacements(boolean obby, CrystalPosition... crystalPositions) {
        if (!module.placeTimer().passed(module.placeDelay().getValue()) || crystalCount >= 1 && !attacked) {
            return;
        }

        preparePlaceCalculation();
        for (CrystalPosition position : crystalPositions) {
            if (position.getOffset().getY() + eyeY > maxY) {
                continue;
            }

            position.computeValidity(antiCheat, level, player, EntityUtil.RANGE, module.maxDeathTime().getValue());
            if (position.isValid() && !position.isObsidian() && calculateDamages(position, level)) {
                if (!position.isKilling() && position.getDamage() < module.minDamage().getValue()) {
                    evaluateAgainst(position, bestFacePlacePos, bestBlockedByCrystalFacePlacePos);
                } else {
                    evaluateAgainst(position, bestPos, bestBlockedByCrystalPos);
                }
            }
        }

        if (bestPos.getValue() != null) {
            place(bestPos.getValue());
            return;
        } else if (bestBlockedByCrystalPos.getValue() != null && tryPlacingPositionBlockedByCrystals(bestBlockedByCrystalPos.getValue())) {
            return;
        }

        if (obby && !placed && module.obbyTimer().passed(500)/* Don't run obsidian calculation too much*/) {
            calculateObsidian(module.positionPool().getPositions());
            module.obbyTimer().reset();
        }

        if (!placed) {
            if (bestFacePlacePos.getValue() != null && shouldFacePlace(bestFacePlacePos.getValue())) {
                place(bestFacePlacePos.getValue());
            } else if (bestBlockedByCrystalFacePlacePos.getValue() != null && shouldFacePlace(bestBlockedByCrystalFacePlacePos.getValue())) {
                tryPlacingPositionBlockedByCrystals(bestBlockedByCrystalFacePlacePos.getValue());
            }
        }
    }

    /**
     * {@link #preparePlaceCalculation()} has to run before this.
     */
    protected void calculateObsidian(CrystalPosition... positions) {
        int obsidian = module.obsidian().getValue();
        if (obsidian <= 0) {
            return;
        }

        Map<BlockPos, Long> blackList = new HashMap<>();
        BlockPathfinderWithBlacklist blockPathfinder = new BlockPathfinderWithBlacklist(blackList);
        BlockStateLevel.Delegating level = new BlockStateLevel.Delegating(this.level);
        for (CrystalPosition position : positions) {
            if (position.getOffset().getY() + eyeY > maxY || !position.isValid() || !position.isObsidian()) {
                continue;
            }

            level.getMap().clear();
            blackList.clear();
            mutPos.set(position);
            mutPos.incrementY(1);
            blackList.put(mutPos, 0L);
            List<BlockPos> path = blockPathfinder.getShortestPath(position, Blocks.OBSIDIAN, player, level, obsidian, module, module.getBlockPlacer());
            if (path.isEmpty()) {
                continue;
            }

            for (int i = 1/* first in path is the goal */; i < path.size(); i++) {
                level.getMap().put(path.get(i), Blocks.OBSIDIAN.defaultBlockState());
            }

            if (phobot.getAntiCheat().getCrystalStrictDirectionCheck().getStrictDirection(position, player, level) == null) {
                continue;
            }

            if (calculateDamages(position, level)
                    && (position.getDamage() > module.minObbyDamage().getValue() || position.isKilling() && position.getDamage() > module.minDamage().getValue())) {
                position.setPath(path);
                evaluateAgainst(position, bestObbyPos, bestBlockedByCrystalObbyPos);
            }
        }

        if (bestObbyPos.getValue() != null) {
            place(bestObbyPos.getValue());
        } else if (bestBlockedByCrystalObbyPos.getValue() != null) {
            tryPlacingPositionBlockedByCrystals(bestBlockedByCrystalObbyPos.getValue());
        }
    }

    private boolean calculateDamages(CrystalPosition position, Level level) {
        float selfDamage = getSelfPlaceDamage(position, level);
        position.setSelfDamage(selfDamage);
        if (selfDamage < EntityUtil.getHealth(player)) {
            for (Player enemy : level.players()) {
                if (isValidPlayer(enemy, position.getX() + 0.5, position.getY() + 1, position.getZ() + 0.5)) {
                    float damage = getPlaceDamage(position, enemy, level);
                    if (damage > position.getDamage()) {
                        position.setDamage(damage);
                        position.setTargetId(enemy.getId());
                        if (EntityUtil.getHealth(enemy) <= module.faceplace().getValue()) {
                            position.setFaceplacingForAReason(true);
                        }

                        if (module.armor().getValue() > 0.0) {
                            for (ItemStack stack : enemy.getInventory().armor) {
                                if (!stack.isEmpty()) {
                                    float durability = ((float) (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage()) * 100;
                                    if (durability >= 0.0 && durability < module.armor().getValue()) {
                                        position.setFaceplacingForAReason(true);
                                        break;
                                    }
                                }
                            }
                        }

                        if (damage >= EntityUtil.getHealth(enemy)) {
                            position.setKilling(true);
                        }
                    }
                }
            }

            return position.getDamage() > 1.0 && (position.isKilling() || position.getRatio(module.balance().getValue()) >= module.minDamageFactor().getValue());
        }

        return false;
    }

    private boolean shouldFacePlace(CrystalPosition crystalPosition) {
        if (module.placeTimer().passed(InvincibilityFrameService.FRAME_LENGTH_MS) || module.fastWhenUnsafe().getValue() && !module.surroundService().isSurrounded()) {
            if (!crystalPosition.isFaceplacingForAReason()) {
                return crystalPosition.getDamage() > 1.0f
                        && (module.faceplaceWhenUnsafe().getValue() && !module.surroundService().isSurrounded() // TODO: some delay so this does not interfere with surround
                                || phobot.getPingBypass().getKeyBoardAndMouse().isPressed(module.facePlaceBind().getValue()) && mc.screen == null);
            }

            return true;
        }

        return false;
    }

    private void evaluateAgainst(CrystalPosition position, MutableObject<CrystalPosition> bestPos, MutableObject<CrystalPosition> bestBlockedByCrystalPos) {
        if (position.isBlockedByCrystal() && !attacked) {
            if (position.isBetterThan(bestBlockedByCrystalPos.getValue(), module.balance().getValue())) {
                bestBlockedByCrystalPos.setValue(position);
            }
        } else if (position.isBetterThan(bestPos.getValue(), module.balance().getValue())) {
            bestPos.setValue(position);
        }
    }

    private boolean tryPlacingPositionBlockedByCrystals(CrystalPosition crystalPosition) {
        if (!attacked && !module.breakTimer().passed(module.breakDelay().getValue())) {
            return false;
        }

        if (!hasBreakingCalculationRun) {
            breakCrystals();
        }

        if (attacked) {
            place(crystalPosition);
            return true;
        }

        Entity bestBlockingCrystal = null;
        float lowestSelfDamage = Float.MAX_VALUE;
        for (Entity crystal : crystalPosition.getCrystals()) {
            if (crystal != null && crystal.isAlive()
                    && EntityUtil.isInAttackRange(player, crystal)
                    && level.getWorldBorder().isWithinBounds(crystal.blockPosition())) {
                float selfDamage = getSelfBreakDamage(crystal);
                if (selfDamage < lowestSelfDamage) {
                    bestBlockingCrystal = crystal;
                    lowestSelfDamage = selfDamage;
                }
            }
        }

        if (bestBlockingCrystal != null && lowestSelfDamage < EntityUtil.getHealth(player) && (lowestSelfDamage <= crystalPosition.getSelfDamage())) {
            if (attack(bestBlockingCrystal)) {
                place(crystalPosition);
                return true;
            }
        }

        return false;
    }

    private void place(CrystalPosition crystalPosition) {
        CrystalPlacingAction action = module.placer().placeAction(player, level, crystalPosition);
        if (action != null) {
            placed = true;
            if (!crystalPosition.isObsidian() && action.isFailedDueToRotations() && action.isExecuted() && !action.isSuccessful()) {
                module.rotationAction(action);
            }
        }
    }

    protected float getSelfPlaceDamage(BlockPos pos, Level level) {
        return RetryUtil.retryOrThrow(3, () -> RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD, () -> calculator.getDamage(getDamageEntity(player, true), level, pos));
    }

    protected float getPlaceDamage(BlockPos pos, Player enemy, Level level) {
        return RetryUtil.retryOrThrow(3, () -> RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD, () -> calculator.getDamage(getDamageEntity(enemy, true), level, pos));
    }

    protected double getMaxY() {
        double maxY = 0.0;
        for (Player enemy : level.players()) {
            if (EntityUtil.isEnemyInRange(phobot.getPingBypass(), player, enemy, EntityUtil.RANGE + CrystalPlacingModule.CRYSTAL_RADIUS)) {
                maxY = enemy.getY() + 1;
            }
        }

        return maxY;
    }

}
