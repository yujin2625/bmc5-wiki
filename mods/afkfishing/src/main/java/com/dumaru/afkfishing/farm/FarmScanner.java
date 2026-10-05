package com.dumaru.afkfishing.farm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 농장 범위를 여러 틱에 나눠 훑어서 작물과 빈 흙을 찾는다. 한 바퀴 다 돌면 결과를 바꿔 끼운다.
 * 범위 안의 블록은 클라이언트에 로드된 것만 볼 수 있다.
 */
public final class FarmScanner {
    public static final long MAX_VOLUME = 400_000;
    private static final int BLOCKS_PER_TICK = 6000;

    /** 작물별 개수 (GUI 목록과 HUD용). */
    public record Count(int ripe, int total) {
    }

    public record Snapshot(List<Crops.CropAt> crops, List<BlockPos> emptySoil, Map<String, Count> counts, long time) {
        public static final Snapshot EMPTY = new Snapshot(List.of(), List.of(), Map.of(), 0);
    }

    private Snapshot snapshot = Snapshot.EMPTY;
    private BlockPos min;
    private BlockPos max;
    private int cursorX;
    private int cursorY;
    private int cursorZ;
    private boolean scanning;
    private List<Crops.CropAt> building;
    private List<BlockPos> buildingSoil;

    public Snapshot snapshot() {
        return snapshot;
    }

    public void clear() {
        snapshot = Snapshot.EMPTY;
        scanning = false;
    }

    public boolean isScanning() {
        return scanning;
    }

    /** 새 스캔을 시작한다 (진행 중이면 처음부터). */
    public void start(FarmData data) {
        if (!data.hasArea() || data.volume() > MAX_VOLUME) {
            scanning = false;
            return;
        }
        min = data.min();
        BlockPos m = data.max();
        max = new BlockPos(m.getX(), m.getY() + FarmData.scanAbove(), m.getZ());
        cursorX = min.getX();
        cursorY = min.getY();
        cursorZ = min.getZ();
        building = new ArrayList<>();
        buildingSoil = new ArrayList<>();
        scanning = true;
    }

    /** 한 틱 분량을 훑는다. 한 바퀴를 마치면 true. */
    public boolean tick(Level level, FarmData data) {
        if (!scanning) {
            return false;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int n = 0; n < BLOCKS_PER_TICK; n++) {
            pos.set(cursorX, cursorY, cursorZ);
            if (level.isLoaded(pos)) {
                visit(level, data, pos.immutable());
            }
            if (++cursorX > max.getX()) {
                cursorX = min.getX();
                if (++cursorZ > max.getZ()) {
                    cursorZ = min.getZ();
                    if (++cursorY > max.getY()) {
                        finish();
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void visit(Level level, FarmData data, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        Crops.CropAt crop = Crops.classify(level, pos, state);
        if (crop != null) {
            building.add(crop);
            if (crop.kind() == Crops.Kind.AGE && Crops.isSoil(level.getBlockState(pos.below()))) {
                data.plantMemory.put(FarmData.posKey(pos.below()), crop.id());
            }
            return;
        }
        if (Crops.isSoil(state) && level.getBlockState(pos.above()).isAir()) {
            buildingSoil.add(pos);
        }
    }

    private void finish() {
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (Crops.CropAt c : building) {
            int[] n = counts.computeIfAbsent(c.id(), k -> new int[2]);
            n[1]++;
            if (c.ripe()) {
                n[0]++;
            }
        }
        Map<String, Count> result = new LinkedHashMap<>();
        counts.forEach((id, n) -> result.put(id, new Count(n[0], n[1])));
        snapshot = new Snapshot(List.copyOf(building), List.copyOf(buildingSoil), result, System.currentTimeMillis());
        scanning = false;
    }
}
