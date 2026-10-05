package com.yujin.mobalert;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 클라이언트 전용 명령어:
 * /mobalert [gui] | add|remove &lt;entity&gt; | list | clear | radius &lt;n&gt; | cooldown &lt;s&gt; | on | off
 * /mobalert toggle glow|hud|toast|sound|chat
 */
public final class MobAlertCommands {
    private static final DynamicCommandExceptionType UNKNOWN_ENTITY =
            new DynamicCommandExceptionType(id -> Component.translatable("mobalert.cmd.unknown", String.valueOf(id)));

    private MobAlertCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("mobalert")
                .executes(c -> openGui())
                .then(Commands.literal("gui").executes(c -> openGui()))
                .then(Commands.literal("add").then(Commands.argument("entity", StringArgumentType.greedyString())
                        .suggests((c, b) -> suggestContaining(addSuggestions(), b))
                        .executes(c -> setWatched(c, true))))
                .then(Commands.literal("remove").then(Commands.argument("entity", StringArgumentType.greedyString())
                        .suggests((c, b) -> suggestContaining(MobAlertConfig.get().entities, b))
                        .executes(c -> setWatched(c, false))))
                .then(Commands.literal("list").executes(MobAlertCommands::list))
                .then(Commands.literal("clear").executes(c -> {
                    MobAlertConfig.get().clearWatched();
                    MobAlertConfig.save();
                    return feedback(c, Component.translatable("mobalert.cmd.cleared"));
                }))
                .then(Commands.literal("radius").then(Commands.argument("blocks",
                                IntegerArgumentType.integer(MobAlertConfig.MIN_RADIUS, MobAlertConfig.MAX_RADIUS))
                        .executes(c -> {
                            MobAlertConfig.get().radius = IntegerArgumentType.getInteger(c, "blocks");
                            MobAlertConfig.save();
                            return feedback(c, Component.translatable("mobalert.cmd.radius", MobAlertConfig.get().radius));
                        })))
                .then(Commands.literal("cooldown").then(Commands.argument("seconds",
                                IntegerArgumentType.integer(0, MobAlertConfig.MAX_COOLDOWN))
                        .executes(c -> {
                            MobAlertConfig.get().cooldownSeconds = IntegerArgumentType.getInteger(c, "seconds");
                            MobAlertConfig.save();
                            return feedback(c, Component.translatable("mobalert.cmd.cooldown", MobAlertConfig.get().cooldownSeconds));
                        })))
                .then(Commands.literal("on").executes(c -> setFlag(c, "enabled", true, (cfg, v) -> cfg.enabled = v)))
                .then(Commands.literal("off").executes(c -> setFlag(c, "enabled", false, (cfg, v) -> cfg.enabled = v)))
                .then(Commands.literal("toggle")
                        .then(toggle("glow", cfg -> cfg.glow, (cfg, v) -> cfg.glow = v))
                        .then(toggle("hud", cfg -> cfg.hud, (cfg, v) -> cfg.hud = v))
                        .then(toggle("toast", cfg -> cfg.toast, (cfg, v) -> cfg.toast = v))
                        .then(toggle("sound", cfg -> cfg.sound, (cfg, v) -> cfg.sound = v))
                        .then(toggle("chat", cfg -> cfg.chat, (cfg, v) -> cfg.chat = v))
                        .then(toggle("map", cfg -> cfg.map, (cfg, v) -> cfg.map = v))
                        .then(toggle("marker", cfg -> cfg.marker, (cfg, v) -> cfg.marker = v))
                        .then(toggle("pet", cfg -> cfg.petGuard, (cfg, v) -> cfg.petGuard = v))
                        .then(toggle("petsweep", cfg -> cfg.petGuardSweep, (cfg, v) -> cfg.petGuardSweep = v)));
        d.register(root);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> toggle(String name, Function<MobAlertConfig, Boolean> getter,
                                                                     BiConsumer<MobAlertConfig, Boolean> setter) {
        return Commands.literal(name).executes(c -> setFlag(c, name, !getter.apply(MobAlertConfig.get()), setter));
    }

    private static int setFlag(CommandContext<CommandSourceStack> c, String name, boolean value,
                               BiConsumer<MobAlertConfig, Boolean> setter) {
        setter.accept(MobAlertConfig.get(), value);
        MobAlertConfig.save();
        return feedback(c, Component.translatable("mobalert.cmd.flag",
                Component.translatable("mobalert.option." + name),
                Component.translatable(value ? "options.on" : "options.off")));
    }

    private static int openGui() {
        MobAlert.requestOpenScreen();
        return 1;
    }

    private static int setWatched(CommandContext<CommandSourceStack> c, boolean watched) throws CommandSyntaxException {
        String key = StringArgumentType.getString(c, "entity").trim();
        int sep = key.indexOf(Variants.SEP);
        ResourceLocation id = ResourceLocation.tryParse(sep > 0 ? key.substring(0, sep) : key);
        if (watched && (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id))) throw UNKNOWN_ENTITY.create(key);
        if (id != null) key = sep > 0 ? id + key.substring(sep) : id.toString(); // "panda" → "minecraft:panda"
        MobAlertConfig.get().setWatched(key, watched);
        MobAlertConfig.save();
        return feedback(c, Component.translatable(watched ? "mobalert.cmd.added" : "mobalert.cmd.removed", Variants.bothNames(key)));
    }

    /** 입력한 글자가 어디에든 포함되면 제안 ("panda" → minecraft:panda, "nether" → dmr:dragon#...nether). */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestContaining(
            Iterable<String> candidates, com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        String q = b.getRemainingLowerCase();
        for (String s : candidates) if (s.contains(q)) b.suggest(s);
        return b.buildFuture();
    }

    /** 생물 id 전체 + 알려진 품종 키("dmr:dragon#dmr.dragon_breed.nether"). */
    private static List<String> addSuggestions() {
        List<String> out = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
            out.add(id);
            for (String vk : Variants.variantsOf(type)) out.add(id + Variants.SEP + vk);
        }
        return out;
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        var keys = MobAlertConfig.get().entities;
        if (keys.isEmpty()) return feedback(c, Component.translatable("mobalert.cmd.list_empty"));
        MutableComponent msg = Component.translatable("mobalert.cmd.list", keys.size());
        for (String key : keys) {
            msg.append(Component.literal("\n - ")).append(Variants.bothNames(key))
                    .append(Component.literal(" [" + key + "]").withStyle(ChatFormatting.DARK_GRAY));
        }
        return feedback(c, msg);
    }

    private static int feedback(CommandContext<CommandSourceStack> c, Component msg) {
        c.getSource().sendSuccess(() -> Component.literal("[Mob Alert] ").withStyle(ChatFormatting.GOLD)
                .append(msg.copy().withStyle(ChatFormatting.WHITE)), false);
        return 1;
    }
}
