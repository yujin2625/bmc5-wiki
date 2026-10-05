package com.dumaru.afkfishing.farm;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

/** 블록이 어떤 작물인지, 다 자랐는지, 어떻게 수확하는지 판정한다. */
public final class Crops {
    public enum Kind {
        /** 나이(age)가 최대이면 우클릭 (Right Click Harvest가 수확 후 다시 심음). 밀·당근·네더 사마귀·코코아 등 */
        AGE,
        /** 우클릭으로 열매만 땀. 달콤한 열매 덤불 */
        BUSH,
        /** 줄기에 달린 열매 블록을 부숨. 수박·호박 */
        GOURD,
        /** 두 칸 이상 자라면 밑동 위를 부숨. 사탕수수·대나무·선인장 */
        STACK
    }

    /** 스캔 결과 한 칸. actionPos = 우클릭하거나 부술 위치. */
    public record CropAt(String id, Kind kind, BlockPos pos, BlockPos actionPos, boolean ripe) {
    }

    // 알려진 작물의 수확물 (그 밖의 작물은 수확할 때 떨어진 아이템으로 학습한다)
    private static final Map<String, Set<Item>> KNOWN_PRODUCTS = new HashMap<>();

    static {
        known(Blocks.WHEAT, Items.WHEAT, Items.WHEAT_SEEDS);
        known(Blocks.CARROTS, Items.CARROT);
        known(Blocks.POTATOES, Items.POTATO, Items.POISONOUS_POTATO);
        known(Blocks.BEETROOTS, Items.BEETROOT, Items.BEETROOT_SEEDS);
        known(Blocks.NETHER_WART, Items.NETHER_WART);
        known(Blocks.COCOA, Items.COCOA_BEANS);
        known(Blocks.SWEET_BERRY_BUSH, Items.SWEET_BERRIES);
        known(Blocks.MELON, Items.MELON_SLICE, Items.MELON);
        known(Blocks.PUMPKIN, Items.PUMPKIN);
        known(Blocks.SUGAR_CANE, Items.SUGAR_CANE);
        known(Blocks.BAMBOO, Items.BAMBOO);
        known(Blocks.CACTUS, Items.CACTUS);
    }

    private Crops() {
    }

    private static void known(Block block, Item... items) {
        KNOWN_PRODUCTS.put(id(block), new HashSet<>(Set.of(items)));
    }

    public static String id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    public static Block block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
    }

    public static String displayName(String id) {
        return block(id).getName().getString();
    }

    public static Set<Item> knownProducts(String id) {
        return KNOWN_PRODUCTS.getOrDefault(id, Set.of());
    }

    /**
     * 이 칸이 작물이면 판정 결과, 아니면 null.
     * 물속 작물(쌀 등)은 아직 지원하지 않으므로 제외한다.
     */
    public static CropAt classify(Level level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block instanceof AttachedStemBlock || block instanceof StemBlock || block instanceof PitcherCropBlock || state.isAir()) {
            return null;
        }
        if (block == Blocks.MELON || block == Blocks.PUMPKIN) {
            return isAttachedGourd(level, pos) ? new CropAt(id(block), Kind.GOURD, pos, pos, true) : null;
        }
        if (block instanceof SugarCaneBlock || block instanceof BambooStalkBlock || block instanceof CactusBlock) {
            if (level.getBlockState(pos.below()).is(block)) {
                return null; // 밑동만 대표로 센다
            }
            boolean ripe = level.getBlockState(pos.above()).is(block);
            return new CropAt(id(block), Kind.STACK, pos, pos.above(), ripe);
        }
        if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.below()).isEmpty()) {
            return null; // 물속 작물 제외 (쌀 등)
        }
        if (block instanceof SweetBerryBushBlock) {
            return new CropAt(id(block), Kind.BUSH, pos, pos, state.getValue(SweetBerryBushBlock.AGE) >= 3);
        }
        if (block instanceof CropBlock crop) {
            return new CropAt(id(block), Kind.AGE, pos, pos, crop.isMaxAge(state));
        }
        if (block instanceof NetherWartBlock) {
            return new CropAt(id(block), Kind.AGE, pos, pos, state.getValue(NetherWartBlock.AGE) >= 3);
        }
        if (block instanceof CocoaBlock) {
            return new CropAt(id(block), Kind.AGE, pos, pos, state.getValue(CocoaBlock.AGE) >= 2);
        }
        if (state.is(BlockTags.CROPS)) {
            // 모드 작물: age 속성이 있으면 최댓값을 다 자란 것으로 본다.
            for (Property<?> property : state.getProperties()) {
                if (property instanceof IntegerProperty age && property.getName().equals("age")) {
                    int max = age.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(0);
                    return new CropAt(id(block), Kind.AGE, pos, pos, state.getValue(age) >= max);
                }
            }
        }
        return null;
    }

    private static boolean isAttachedGourd(Level level, BlockPos pos) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockState stem = level.getBlockState(pos.relative(dir));
            if (stem.getBlock() instanceof AttachedStemBlock && stem.getValue(AttachedStemBlock.FACING) == dir.getOpposite()) {
                return true;
            }
        }
        return false;
    }

    /** 다시 심을 때 쓰는 씨앗 (AGE 작물만). 없으면 빈 스택. */
    public static ItemStack seedOf(Level level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock || block instanceof NetherWartBlock) {
            return block.getCloneItemStack(level, pos, state);
        }
        return ItemStack.EMPTY;
    }

    public static ItemStack seedOf(Level level, String id) {
        Block block = block(id);
        return seedOf(level, BlockPos.ZERO, block.defaultBlockState());
    }

    /** 씨앗을 심을 수 있는 흙 (농경지, 영혼 모래). */
    public static boolean isSoil(BlockState state) {
        return state.getBlock() instanceof FarmBlock || state.is(Blocks.SOUL_SAND);
    }
}
