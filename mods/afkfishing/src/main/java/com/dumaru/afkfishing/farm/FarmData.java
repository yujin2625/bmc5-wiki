package com.dumaru.afkfishing.farm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * 농장 하나의 설정 (범위, 상자, 작물, 낚시 자리 등). 서버와 차원마다 따로 JSON 파일로 저장한다.
 * config/afkfishing/farms/<서버>_<차원>.json
 */
public final class FarmData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static FarmData current;
    private static String currentKey;

    public static final class ChestEntry {
        public int x;
        public int y;
        public int z;
        /** 이 상자에 넣을 작물 id. catchAll이면 무시 */
        public Set<String> crops = new LinkedHashSet<>();
        /** 작물 상자에 해당하지 않는, 이번에 새로 얻은 아이템(물고기 등)을 넣는 상자 */
        public boolean catchAll;
        /** 범위 안에서 자동으로 찾은 상자 (저장하지 않음) */
        public transient boolean auto;

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    public static final class FishSpot {
        public double x;
        public double y;
        public double z;
        public float yaw;
        public float pitch;

        public Vec3 pos() {
            return new Vec3(x, y, z);
        }
    }

    // 저장되는 값
    public int[] corner1;
    public int[] corner2;
    public List<ChestEntry> chests = new ArrayList<>();
    public Set<String> selectedCrops = new LinkedHashSet<>();
    public FishSpot fishSpot;
    /** 낚시로 얻은 것을 넣을 상자 (바닐라 낚시, 물고기가 아닌 전리품) */
    public int[] fishChest;
    /** Star Catcher 물고기를 넣을 태클박스 */
    public int[] tackleBox;
    /** 자동 정리에서 뺄 상자 ("x,y,z") */
    public Set<String> blacklistChests = new LinkedHashSet<>();
    /** 빈 농경지에 심을 기본 작물 (그 자리에 뭐가 있었는지 모를 때). null이면 심지 않음 */
    public String defaultPlant;
    /** 흙 위치("x,y,z") → 마지막으로 본 작물 id */
    public Map<String, String> plantMemory = new HashMap<>();
    /** 작물 id → 수확할 때 떨어진 걸로 확인된 아이템 id */
    public Map<String, Set<String>> learnedProducts = new HashMap<>();

    private transient String key;

    /** 지금 접속한 서버·차원의 농장 설정. 바뀌었으면 다시 불러온다. */
    public static FarmData current() {
        String key = currentKey();
        if (current == null || !key.equals(currentKey)) {
            current = load(key);
            currentKey = key;
        }
        return current;
    }

    private static String currentKey() {
        Minecraft mc = Minecraft.getInstance();
        String server;
        ServerData data = mc.getCurrentServer();
        if (mc.getSingleplayerServer() != null) {
            server = "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName();
        } else if (data != null) {
            server = data.ip;
        } else {
            server = "unknown";
        }
        String dim = mc.level != null ? mc.level.dimension().location().toString() : "none";
        return (server + "_" + dim).replaceAll("[^A-Za-z0-9가-힣._-]", "_");
    }

    private static Path file(String key) {
        return FMLPaths.CONFIGDIR.get().resolve("afkfishing").resolve("farms").resolve(key + ".json");
    }

    private static FarmData load(String key) {
        Path path = file(key);
        FarmData data = null;
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                data = GSON.fromJson(reader, FarmData.class);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("농장 설정을 읽지 못했습니다: {}", path, e);
            }
        }
        if (data == null) {
            data = new FarmData();
        }
        if (data.chests == null) {
            data.chests = new ArrayList<>();
        }
        if (data.selectedCrops == null) {
            data.selectedCrops = new LinkedHashSet<>();
        }
        if (data.plantMemory == null) {
            data.plantMemory = new HashMap<>();
        }
        if (data.blacklistChests == null) {
            data.blacklistChests = new LinkedHashSet<>();
        }
        if (data.learnedProducts == null) {
            data.learnedProducts = new HashMap<>();
        }
        data.key = key;
        return data;
    }

    public void save() {
        Path path = file(key);
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("농장 설정을 저장하지 못했습니다: {}", path, e);
        }
    }

    // ---- 편의 ----

    public boolean hasArea() {
        return corner1 != null && corner2 != null;
    }

    public BlockPos min() {
        return new BlockPos(Math.min(corner1[0], corner2[0]), Math.min(corner1[1], corner2[1]), Math.min(corner1[2], corner2[2]));
    }

    public BlockPos max() {
        return new BlockPos(Math.max(corner1[0], corner2[0]), Math.max(corner1[1], corner2[1]), Math.max(corner1[2], corner2[2]));
    }

    /** 범위 상자. 작물은 지정한 높이 범위 위로 한 칸까지 본다 (농경지를 찍어도 작물이 들어가게). */
    public AABB areaBox() {
        BlockPos min = min();
        BlockPos max = max();
        return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 2, max.getZ() + 1);
    }

    public long volume() {
        BlockPos min = min();
        BlockPos max = max();
        return (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 2) * (max.getZ() - min.getZ() + 1);
    }

    public static int[] arr(BlockPos pos) {
        return new int[]{pos.getX(), pos.getY(), pos.getZ()};
    }

    public static BlockPos pos(int[] a) {
        return new BlockPos(a[0], a[1], a[2]);
    }

    public static String posKey(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public ChestEntry chestAt(BlockPos pos) {
        for (ChestEntry c : chests) {
            if (c.x == pos.getX() && c.y == pos.getY() && c.z == pos.getZ()) {
                return c;
            }
        }
        return null;
    }

    /** 상자를 등록하거나 이미 있으면 그 항목을 돌려준다. */
    public ChestEntry addChest(BlockPos pos) {
        ChestEntry entry = chestAt(pos);
        if (entry == null) {
            entry = new ChestEntry();
            entry.x = pos.getX();
            entry.y = pos.getY();
            entry.z = pos.getZ();
            chests.add(entry);
        }
        return entry;
    }

    public List<ChestEntry> chestsFor(String cropId) {
        List<ChestEntry> list = new ArrayList<>();
        for (ChestEntry c : chests) {
            if (!c.catchAll && c.crops.contains(cropId)) {
                list.add(c);
            }
        }
        return list;
    }

    /** 자동 정리 제외 상자인지 (큰 상자는 다른 쪽이 제외돼 있어도 제외). */
    public boolean isBlacklisted(BlockPos pos, BlockPos otherHalf) {
        return blacklistChests.contains(posKey(pos)) || (otherHalf != null && blacklistChests.contains(posKey(otherHalf)));
    }

    public static BlockPos parsePos(String key) {
        String[] p = key.split(",");
        return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }

    public void learn(String cropId, String itemId) {
        learnedProducts.computeIfAbsent(cropId, k -> new HashSet<>()).add(itemId);
    }
}
