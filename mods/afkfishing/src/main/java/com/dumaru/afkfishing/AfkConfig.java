package com.dumaru.afkfishing;

import com.dumaru.afkfishing.common.AutoEater;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class AfkConfig {
    public enum MovePattern {
        BACK_FORTH("앞뒤 한 걸음"),
        LEFT_RIGHT("좌우 한 걸음"),
        JUMP("제자리 점프");

        public final String label;

        MovePattern(String label) {
            this.label = label;
        }
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // 낚시 타이밍 (단위: 틱, 20틱 = 1초)
    public static final ModConfigSpec.IntValue REEL_DELAY_MIN = BUILDER
            .comment("입질 후 회수까지 최소 지연 (틱)")
            .defineInRange("reelDelayMin", 4, 0, 40);
    public static final ModConfigSpec.IntValue REEL_DELAY_MAX = BUILDER
            .comment("입질 후 회수까지 최대 지연 (틱)")
            .defineInRange("reelDelayMax", 10, 0, 40);
    public static final ModConfigSpec.IntValue RECAST_DELAY_MIN = BUILDER
            .comment("회수 후 재투척까지 최소 지연 (틱)")
            .defineInRange("recastDelayMin", 12, 5, 100);
    public static final ModConfigSpec.IntValue RECAST_DELAY_MAX = BUILDER
            .comment("회수 후 재투척까지 최대 지연 (틱)")
            .defineInRange("recastDelayMax", 30, 5, 100);
    public static final ModConfigSpec.IntValue BITE_TIMEOUT_SECONDS = BUILDER
            .comment("입질이 이 시간(초) 동안 없으면 회수 후 재투척")
            .defineInRange("biteTimeoutSeconds", 90, 30, 300);

    // 안전장치
    public static final ModConfigSpec.IntValue MIN_DURABILITY = BUILDER
            .comment("낚싯대 남은 내구도가 이 값 이하이면 사용하지 않음")
            .defineInRange("minDurability", 5, 0, 64);
    public static final ModConfigSpec.BooleanValue AUTO_SWAP_ROD = BUILDER
            .comment("내구도가 부족하면 인벤토리의 다른 낚싯대로 교체")
            .define("autoSwapRod", true);
    public static final ModConfigSpec.BooleanValue STOP_WHEN_FULL = BUILDER
            .comment("인벤토리에 빈 칸이 없으면 정지")
            .define("stopWhenInventoryFull", true);
    public static final ModConfigSpec.BooleanValue STOP_WHEN_LURE_GONE = BUILDER
            .comment("왼손 낚싯바늘(Hybrid Aquatic)이 다 닳고 예비도 없으면 정지. 끄면 바늘 없이 계속 낚시")
            .define("stopWhenLureGone", true);
    public static final ModConfigSpec.BooleanValue STOP_ON_DAMAGE = BUILDER
            .comment("피해를 입으면 정지")
            .define("stopOnDamage", true);

    // AFK 킥 방지 이동
    public static final ModConfigSpec.BooleanValue ANTI_AFK = BUILDER
            .comment("주기적으로 실제 이동해서 AFK 킥 방지")
            .define("antiAfkEnabled", true);
    public static final ModConfigSpec.IntValue ANTI_AFK_INTERVAL_SECONDS = BUILDER
            .comment("이동 주기 (초). ±30초 랜덤")
            .defineInRange("antiAfkIntervalSeconds", 300, 60, 840);
    public static final ModConfigSpec.EnumValue<MovePattern> MOVE_PATTERN = BUILDER
            .comment("이동 패턴")
            .defineEnum("movePattern", MovePattern.BACK_FORTH);
    public static final ModConfigSpec.IntValue MOVE_TICKS = BUILDER
            .comment("한 방향으로 걷는 시간 (틱)")
            .defineInRange("moveTicks", 4, 2, 10);

    // 밤에 침낭으로 자기 (Comforts)
    public static final ModConfigSpec.BooleanValue SLEEP_AT_NIGHT = BUILDER
            .comment("밤이 되면 인벤토리의 침낭(Comforts)을 펼쳐서 자고, 일어나면 침낭을 회수한 뒤 제자리로 돌아와 낚시를 이어감")
            .define("sleepAtNight", true);
    public static final ModConfigSpec.IntValue BAG_SEARCH_RADIUS = BUILDER
            .comment("침낭 펼칠 자리를 찾는 반경 (칸). 3칸보다 멀면 걸어가서 펼치고, 일어난 뒤 걸어서 돌아옴")
            .defineInRange("bagSearchRadius", 3, 1, 10);

    public static final ModConfigSpec.BooleanValue SHOW_HUD = BUILDER
            .comment("화면에 상태 HUD 표시")
            .define("showHud", true);

    // 자동으로 먹기 (공통)
    public static final ModConfigSpec.EnumValue<AutoEater.FoodMode> FOOD_MODE = BUILDER
            .comment("배고프면 자동으로 먹기. NON_CROP = 농사로 거둔 작물은 먹지 않음")
            .defineEnum("foodMode", AutoEater.FoodMode.NON_CROP);
    public static final ModConfigSpec.IntValue EAT_BELOW = BUILDER
            .comment("배고픔 수치가 이 값 이하이면 먹음 (최대 20)")
            .defineInRange("eatBelow", 14, 1, 19);
    public static final ModConfigSpec.IntValue FOOD_KEEP = BUILDER
            .comment("상자에 정리할 때 인벤토리에 남겨 둘 먹을 음식 수")
            .defineInRange("foodKeep", 16, 0, 64);

    // 자동 농사
    public static final ModConfigSpec.IntValue FARM_MIN_RIPE = BUILDER
            .comment("다 자란 작물이 이 개수 이상 모이면 수확하러 감")
            .defineInRange("farmMinRipe", 8, 1, 256);
    public static final ModConfigSpec.IntValue FARM_MIN_RIPE_PERCENT = BUILDER
            .comment("또는 선택한 작물 중 이 비율(%) 이상이 다 자라면 수확하러 감. 0이면 사용 안 함")
            .defineInRange("farmMinRipePercent", 50, 0, 100);
    public static final ModConfigSpec.IntValue FARM_MAX_WAIT_MINUTES = BUILDER
            .comment("다 자란 작물이 하나라도 이 시간(분) 넘게 기다리면 개수와 상관없이 수확하러 감")
            .defineInRange("farmMaxWaitMinutes", 10, 1, 120);
    public static final ModConfigSpec.BooleanValue FARM_USE_HOE = BUILDER
            .comment("괭이를 들고 수확 (Right Click Harvest가 괭이 등급에 따라 주변도 같이 수확)")
            .define("farmUseHoe", true);
    public static final ModConfigSpec.BooleanValue FARM_REPLANT = BUILDER
            .comment("비어 있는 농경지에 그 자리에 있던 작물(또는 기본 작물)의 씨앗을 다시 심음")
            .define("farmReplant", true);
    public static final ModConfigSpec.IntValue FARM_KEEP_SEEDS = BUILDER
            .comment("상자에 정리할 때 다시 심을 씨앗을 작물마다 이만큼 남겨 둠")
            .defineInRange("farmKeepSeeds", 16, 0, 256);
    public static final ModConfigSpec.BooleanValue FARM_BONEMEAL = BUILDER
            .comment("수확할 게 없을 때 덜 자란 작물에 뼛가루 사용")
            .define("farmBonemeal", false);
    public static final ModConfigSpec.IntValue FARM_BONEMEAL_KEEP = BUILDER
            .comment("뼛가루를 이만큼은 쓰지 않고 남겨 둠")
            .defineInRange("farmBonemealKeep", 0, 0, 256);
    public static final ModConfigSpec.BooleanValue FARM_COLLECT_ITEMS = BUILDER
            .comment("농장 안에 떨어진 아이템을 주우러 감")
            .define("farmCollectItems", true);
    public static final ModConfigSpec.IntValue FARM_DEPOSIT_MINUTES = BUILDER
            .comment("이 시간(분)마다 인벤토리의 수확물을 지정한 상자에 정리")
            .defineInRange("farmDepositMinutes", 10, 1, 120);
    public static final ModConfigSpec.IntValue FARM_DEPOSIT_FREE_SLOTS = BUILDER
            .comment("인벤토리 빈칸이 이 수 이하로 남으면 시간과 상관없이 바로 정리")
            .defineInRange("farmDepositFreeSlots", 3, 0, 30);
    public static final ModConfigSpec.BooleanValue FARM_DEPOSIT_BEFORE_SLEEP = BUILDER
            .comment("자러 가기 전에 상자에 정리")
            .define("farmDepositBeforeSleep", true);
    public static final ModConfigSpec.BooleanValue FARM_STOP_WHEN_CHESTS_FULL = BUILDER
            .comment("넣을 상자가 모두 가득 차면 정지. 끄면 알림만 하고 계속")
            .define("farmStopWhenChestsFull", false);
    public static final ModConfigSpec.BooleanValue FARM_WAIT_FISHING = BUILDER
            .comment("수확할 게 없을 때 지정한 낚시 자리에서 낚시하며 기다림")
            .define("farmWaitFishing", true);
    public static final ModConfigSpec.IntValue FARM_TOOL_MIN_DURABILITY = BUILDER
            .comment("괭이·도끼 등 도구의 남은 내구도가 이 값 이하이면 쓰지 않음")
            .defineInRange("farmToolMinDurability", 10, 0, 256);
    public static final ModConfigSpec.BooleanValue FARM_AVOID_FARMLAND = BUILDER
            .comment("이동할 때 되도록 농경지를 밟지 않고 길로 다님")
            .define("farmAvoidFarmland", true);
    // 사람처럼 움직이기 (공통)
    public static final ModConfigSpec.BooleanValue HUMAN_LOOK = BUILDER
            .comment("시선을 한 번에 꺾지 않고 부드럽게 돌림 (약간의 오차 포함)")
            .define("humanLook", true);
    public static final ModConfigSpec.IntValue LOOK_SPEED = BUILDER
            .comment("시선 회전 최대 속도 (도/초)")
            .defineInRange("lookSpeed", 300, 90, 720);
    public static final ModConfigSpec.BooleanValue HUMAN_WALK = BUILDER
            .comment("가는 방향을 바라보며 걷고, 경로를 곧게 다듬고, 출발·정지할 때 가속·감속")
            .define("humanWalk", true);
    public static final ModConfigSpec.BooleanValue SPRINT_LONG = BUILDER
            .comment("멀리 곧게 갈 때는 달림")
            .define("sprintLong", true);
    public static final ModConfigSpec.BooleanValue MINIGAME_HUMAN = BUILDER
            .comment("Star Catcher 미니게임에서 표적 정중앙이 아니라 표적 안 아무 데서나 누름")
            .define("minigameHuman", true);
    public static final ModConfigSpec.IntValue MINIGAME_MISS_PERCENT = BUILDER
            .comment("Star Catcher 미니게임에서 일부러 살짝 빗나가게 누를 확률 (%)")
            .defineInRange("minigameMissPercent", 3, 0, 20);

    public static final ModConfigSpec.BooleanValue FARM_SHOW_AREA = BUILDER
            .comment("농장 범위·상자·낚시 자리를 화면에 테두리로 표시 (설정 화면이 열려 있거나 농사 중일 때)")
            .define("farmShowArea", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private AfkConfig() {
    }
}
