package com.dumaru.afkfishing;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * 바닐라 FishingHook.calculateOpenWater()를 클라이언트에서 그대로 재현한다.
 * 찌 위치 기준 5x5 범위를 y-1 ~ y+2 네 층에 걸쳐 검사하며, 아래층부터 "물층"이 이어지다가
 * 그 위로 "공기층"이 이어져야 한다. 탁 트인 물이 아니면 보물(마법 부여 책 등)이 나오지 않는다.
 */
public final class OpenWaterChecker {
    private enum LayerType { ABOVE_WATER, INSIDE_WATER, INVALID }

    /** reason은 ok가 false일 때만 의미가 있다. */
    public record Result(boolean ok, String reason) {
    }

    private static final Result OK = new Result(true, "");

    private OpenWaterChecker() {
    }

    public static Result check(Level level, BlockPos hookPos) {
        LayerType previous = LayerType.INVALID;
        for (int dy = -1; dy <= 2; dy++) {
            LayerType layer = LayerType.INVALID;
            String invalidReason = null;
            for (int dx = -2; dx <= 2 && invalidReason == null; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos pos = hookPos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    LayerType type = typeOf(level, pos, state);
                    if (type == LayerType.INVALID) {
                        invalidReason = layerName(dy) + "에 " + describe(state) + " " + offsetText(dx, dz);
                        break;
                    }
                    if (layer == LayerType.INVALID) {
                        layer = type;
                    } else if (layer != type) {
                        invalidReason = layerName(dy) + "에 물과 공기가 섞여 있음 (물 범위가 5x5보다 좁음)";
                        break;
                    }
                }
            }
            if (invalidReason != null) {
                return new Result(false, invalidReason);
            }
            if (layer == LayerType.ABOVE_WATER && previous == LayerType.INVALID) {
                return new Result(false, "찌 아래에 물이 없음 (물 깊이 2칸 이상 필요)");
            }
            if (layer == LayerType.INSIDE_WATER && previous == LayerType.ABOVE_WATER) {
                return new Result(false, layerName(dy) + "에 물이 있음 (수면 위 2칸은 비어 있어야 함)");
            }
            previous = layer;
        }
        return OK;
    }

    private static LayerType typeOf(Level level, BlockPos pos, BlockState state) {
        if (state.isAir() || state.is(Blocks.LILY_PAD)) {
            return LayerType.ABOVE_WATER;
        }
        FluidState fluid = state.getFluidState();
        return fluid.is(FluidTags.WATER) && fluid.isSource() && state.getCollisionShape(level, pos).isEmpty()
                ? LayerType.INSIDE_WATER
                : LayerType.INVALID;
    }

    private static String describe(BlockState state) {
        FluidState fluid = state.getFluidState();
        if (state.is(Blocks.WATER) && !fluid.isSource()) {
            return "흐르는 물";
        }
        return state.getBlock().getName().getString();
    }

    private static String layerName(int dy) {
        return switch (dy) {
            case -1 -> "찌 아래 1칸";
            case 0 -> "찌 높이";
            case 1 -> "찌 위 1칸";
            default -> "찌 위 2칸";
        };
    }

    private static String offsetText(int dx, int dz) {
        return "(찌 기준 x" + signed(dx) + ", z" + signed(dz) + ")";
    }

    private static String signed(int v) {
        return v >= 0 ? "+" + v : String.valueOf(v);
    }
}
