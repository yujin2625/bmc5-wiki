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
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.CaveVinesBlock;
import net.minecraft.world.level.block.CaveVinesPlantBlock;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.KelpPlantBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
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
    public record CropAt(String id, Kind kind, BlockPos pos, BlockPos actionPos, boolean ripe, boolean shears) {
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
        known(Blocks.KELP, Items.KELP);
        known(Blocks.CAVE_VINES, Items.GLOW_BERRIES);
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
        if (state.isAir() || block instanceof AttachedStemBlock || block instanceof StemBlock || block instanceof PitcherCropBlock) {
            return null;
        }
        String className = block.getClass().getSimpleName();
        if (className.contains("Budding")) {
            return null; // 덩굴로 자라기 전 어린 토마토 (다 자라면 토마토 블록으로 바뀜)
        }
        // 두 칸짜리 작물(아마 등)은 아래 칸만 센다
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals("half") && state.getValue(property) == DoubleBlockHalf.UPPER) {
                return null;
            }
        }
        // 줄기에 달린 열매 (수박·호박·하늘 호박·창백한 호박 등)
        if (!(block instanceof BushBlock) && isAttachedGourd(level, pos)) {
            return new CropAt(id(block), Kind.GOURD, pos, pos, true, false);
        }
        // 위로 자라는 것: 밑동은 남기고 위를 부순다 (사탕수수·대나무·선인장·다시마·가루 대포·가루 줄기 등)
        if (isStacking(block, className)) {
            Block family = stackFamily(block);
            if (stackFamily(level.getBlockState(pos.below()).getBlock()) == family) {
                return null; // 밑동만 대표로 센다
            }
            boolean ripe = stackFamily(level.getBlockState(pos.above()).getBlock()) == family;
            return new CropAt(id(family), Kind.STACK, pos, pos.above(), ripe, false);
        }
        // 동굴 덩굴(발광 열매): 열매가 달렸으면 우클릭
        if (block instanceof CaveVinesBlock || block instanceof CaveVinesPlantBlock) {
            return new CropAt(id(Blocks.CAVE_VINES), Kind.BUSH, pos, pos, state.getValue(CaveVines.BERRIES), false);
        }
        // 에테르 열매 덤불: 덤불을 부수면 열매가 나오고 줄기만 남아 다시 자란다
        if (className.equals("BerryBushBlock")) {
            return new CropAt(id(block), Kind.GOURD, pos, pos, true, false);
        }
        if (!level.getFluidState(pos).isEmpty()) {
            return null; // 물에 잠긴 칸(쌀 밑동 등)은 수확 대상이 아님. 물 위로 올라온 쌀 이삭은 된다.
        }
        if (block instanceof SweetBerryBushBlock) {
            return new CropAt(id(block), Kind.BUSH, pos, pos, state.getValue(SweetBerryBushBlock.AGE) >= 3, false);
        }
        if (block instanceof CropBlock crop) {
            return new CropAt(id(block), Kind.AGE, pos, pos, crop.isMaxAge(state), false);
        }
        if (block instanceof NetherWartBlock) {
            return new CropAt(id(block), Kind.AGE, pos, pos, state.getValue(NetherWartBlock.AGE) >= 3, false);
        }
        if (block instanceof CocoaBlock) {
            return new CropAt(id(block), Kind.AGE, pos, pos, state.getValue(CocoaBlock.AGE) >= 2, false);
        }
        // 그 밖의 모드 작물·덤불: 나이(age) 속성이 최대면 다 자란 것으로 본다.
        boolean plantLike = state.is(BlockTags.CROPS) || state.is(BlockTags.BEE_GROWABLES) || block instanceof BushBlock
                || className.contains("Bush") || className.contains("Crop");
        if (plantLike && !(block instanceof SaplingBlock)) {
            IntegerProperty age = ageProperty(state);
            if (age != null) {
                int max = age.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(0);
                // Farmer's Delight 버섯 군락은 가위로 수확한다
                boolean shears = className.contains("MushroomColony");
                return new CropAt(id(block), Kind.AGE, pos, pos, state.getValue(age) >= max, shears);
            }
        }
        return null;
    }

    private static IntegerProperty ageProperty(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty age && (property.getName().equals("age") || property.getName().endsWith("_age"))) {
                return age;
            }
        }
        return null;
    }

    private static boolean isStacking(Block block, String className) {
        return block instanceof SugarCaneBlock || block instanceof BambooStalkBlock || block instanceof CactusBlock
                || block instanceof KelpBlock || block instanceof KelpPlantBlock || className.contains("Cane");
    }

    /** 옆에 이 블록을 향한 '열매 달린 줄기'가 있으면 줄기 열매(수박·호박 등). */
    private static boolean isAttachedGourd(Level level, BlockPos pos) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockState stem = level.getBlockState(pos.relative(dir));
            if (stem.getBlock() instanceof AttachedStemBlock && stem.getValue(AttachedStemBlock.FACING) == dir.getOpposite()) {
                return true;
            }
        }
        return false;
    }

    /** 다시마는 맨 위(kelp)와 몸통(kelp_plant)이 다른 블록이라 한 종류로 묶는다. */
    private static Block stackFamily(Block block) {
        return block == Blocks.KELP_PLANT ? Blocks.KELP : block;
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
