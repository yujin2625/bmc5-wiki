package com.dumaru.afkfishing;

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

    public static final ModConfigSpec.BooleanValue SHOW_HUD = BUILDER
            .comment("화면에 상태 HUD 표시")
            .define("showHud", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private AfkConfig() {
    }
}
