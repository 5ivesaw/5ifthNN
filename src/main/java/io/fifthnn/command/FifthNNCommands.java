package io.fifthnn.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.fifthnn.FifthNNClient;
import io.fifthnn.config.FifthNNConfig;
import io.fifthnn.inference.AiController;
import io.fifthnn.recorder.DatasetRecorderManager;
import io.fifthnn.schema.FifthNNSchema;
import io.fifthnn.ui.FifthNNControlScreen;
import io.fifthnn.ui.FifthNNDependencyScreen;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Registers `/fifthnn` and `/5nn` client commands for quick recording, tagging,
 * GUI opening, and Phase 2 ONNX model loading without leaving chat.
 */
public final class FifthNNCommands {
    private FifthNNCommands() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(buildRoot("fifthnn"));
        dispatcher.register(buildRoot("5nn"));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildRoot(String literal) {
        return ClientCommands.literal(literal)
                .executes(ctx -> {
                    openGui();
                    return 1;
                })
                .then(ClientCommands.literal("gui").executes(ctx -> {
                    openGui();
                    return 1;
                }))
                .then(ClientCommands.literal("deps").executes(ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> mc.gui.setScreen(new FifthNNDependencyScreen(null)));
                    return 1;
                }))
                .then(ClientCommands.literal("share").executes(ctx -> {
                    FifthNNClient.getRecorder().exportShareZip(Minecraft.getInstance());
                    return 1;
                }))
                .then(ClientCommands.literal("scrap")
                        .then(ClientCommands.literal("episode").executes(ctx -> {
                            FifthNNClient.getRecorder().scrapCurrentEpisode(Minecraft.getInstance());
                            return 1;
                        })))
                .then(ClientCommands.literal("auto")
                        .then(ClientCommands.literal("on").executes(ctx -> {
                            FifthNNConfig cfg = FifthNNClient.getConfig();
                            cfg.autoRecordBedWars = true;
                            cfg.save();
                            ctx.getSource().sendFeedback(Component.literal("§a[5ifthNN] Automatic BedWars recording ON"));
                            return 1;
                        }))
                        .then(ClientCommands.literal("off").executes(ctx -> {
                            FifthNNConfig cfg = FifthNNClient.getConfig();
                            cfg.autoRecordBedWars = false;
                            cfg.save();
                            ctx.getSource().sendFeedback(Component.literal("§e[5ifthNN] Automatic BedWars recording OFF (use F9 manually)"));
                            return 1;
                        })))
                .then(ClientCommands.literal("hud")
                        .then(ClientCommands.literal("minimal").executes(ctx -> {
                            FifthNNConfig cfg = FifthNNClient.getConfig();
                            cfg.showHudOverlay = true;
                            cfg.minimalHudMode = true;
                            cfg.save();
                            ctx.getSource().sendFeedback(Component.literal("§a[5ifthNN] HUD set to Minimal corner dot"));
                            return 1;
                        }))
                        .then(ClientCommands.literal("full").executes(ctx -> {
                            FifthNNConfig cfg = FifthNNClient.getConfig();
                            cfg.showHudOverlay = true;
                            cfg.minimalHudMode = false;
                            cfg.save();
                            ctx.getSource().sendFeedback(Component.literal("§a[5ifthNN] HUD set to Full telemetry stats"));
                            return 1;
                        })))
                .then(ClientCommands.literal("via18")
                        .then(ClientCommands.literal("on").executes(ctx -> {
                            FifthNNConfig cfg = FifthNNClient.getConfig();
                            cfg.autoSetViaFabricPlus18 = true;
                            cfg.save();
                            io.fifthnn.recorder.ViaFabricPlusBridge.ensureDefault18(cfg);
                            ctx.getSource().sendFeedback(Component.literal("§a[5ifthNN] Auto-set ViaFabricPlus 1.8.x ON"));
                            return 1;
                        }))
                        .then(ClientCommands.literal("off").executes(ctx -> {
                            FifthNNConfig cfg = FifthNNClient.getConfig();
                            cfg.autoSetViaFabricPlus18 = false;
                            cfg.save();
                            ctx.getSource().sendFeedback(Component.literal("§e[5ifthNN] Auto-set ViaFabricPlus 1.8.x OFF"));
                            return 1;
                        })))
                .then(ClientCommands.literal("requeue")
                        .then(ClientCommands.literal("on").executes(ctx -> {
                            FifthNNConfig cfg = FifthNNClient.getConfig();
                            cfg.autoRequeueEnabled = true;
                            cfg.save();
                            ctx.getSource().sendFeedback(Component.literal("§a[5ifthNN] Auto-Requeue ON §7(" + cfg.autoRequeueCommand + ")"));
                            return 1;
                        }))
                        .then(ClientCommands.literal("off").executes(ctx -> {
                            FifthNNConfig cfg = FifthNNClient.getConfig();
                            cfg.autoRequeueEnabled = false;
                            cfg.save();
                            ctx.getSource().sendFeedback(Component.literal("§e[5ifthNN] Auto-Requeue OFF"));
                            return 1;
                        }))
                        .then(ClientCommands.literal("cmd")
                                .then(ClientCommands.argument("command", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            FifthNNConfig cfg = FifthNNClient.getConfig();
                                            cfg.autoRequeueCommand = StringArgumentType.getString(ctx, "command");
                                            cfg.save();
                                            ctx.getSource().sendFeedback(Component.literal("§a[5ifthNN] Auto-Requeue command set to §f" + cfg.autoRequeueCommand));
                                            return 1;
                                        }))))
                .then(ClientCommands.literal("slowmo")
                        .then(ClientCommands.literal("on").executes(ctx -> {
                            FifthNNConfig cfg = FifthNNClient.getConfig();
                            cfg.enableLocalSlowMoSensSync = true;
                            cfg.save();
                            ctx.getSource().sendFeedback(Component.literal("§b[5ifthNN] Local 15-TPS Slow-Mo 0.75x Sens Sync ON (local servers only)"));
                            return 1;
                        }))
                        .then(ClientCommands.literal("off").executes(ctx -> {
                            FifthNNConfig cfg = FifthNNClient.getConfig();
                            cfg.enableLocalSlowMoSensSync = false;
                            cfg.save();
                            ctx.getSource().sendFeedback(Component.literal("§e[5ifthNN] Local 15-TPS Slow-Mo Sens Sync OFF"));
                            return 1;
                        })))
                .then(ClientCommands.literal("helpers").executes(ctx -> {
                    boolean allowed = FifthNNClient.getHelperManager().areHelpersAllowed();
                    ctx.getSource().sendFeedback(Component.literal(
                            allowed
                                    ? "§a[5ifthNN Helpers] PERMITTED (Local/Whitelisted Server)"
                                    : "§c[5ifthNN Helpers] LOCKED OFF (Public / Non-Permitted Server)"
                    ));
                    return 1;
                }))
                .then(ClientCommands.literal("rec")
                        .then(ClientCommands.literal("start")
                                .executes(ctx -> {
                                    FifthNNClient.getRecorder().startRecording(
                                            Minecraft.getInstance(),
                                            FifthNNClient.getConfig().getRecordingModeEnum()
                                    );
                                    return 1;
                                })
                                .then(ClientCommands.argument("mode", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String raw = StringArgumentType.getString(ctx, "mode");
                                            FifthNNSchema.RecordingMode mode = FifthNNSchema.RecordingMode.fromName(raw);
                                            FifthNNClient.getRecorder().startRecording(Minecraft.getInstance(), mode);
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("stop").executes(ctx -> {
                            FifthNNClient.getRecorder().stopRecording(Minecraft.getInstance(), "Command stop");
                            return 1;
                        })))
                .then(ClientCommands.literal("mode")
                        .then(ClientCommands.argument("mode", StringArgumentType.word())
                                .executes(ctx -> {
                                    String raw = StringArgumentType.getString(ctx, "mode");
                                    FifthNNSchema.RecordingMode mode = FifthNNSchema.RecordingMode.fromName(raw);
                                    FifthNNClient.getConfig().setRecordingModeEnum(mode);
                                    ctx.getSource().sendFeedback(Component.literal(
                                            "§a[5ifthNN] Recording mode set to §e" + mode.name() + " §7(" + mode.description + ")"));
                                    return 1;
                                })))
                .then(ClientCommands.literal("server")
                        .then(ClientCommands.argument("profile", StringArgumentType.word())
                                .executes(ctx -> {
                                    String srv = StringArgumentType.getString(ctx, "profile");
                                    FifthNNConfig cfg = FifthNNClient.getConfig();
                                    cfg.serverProfile = srv;
                                    cfg.save();
                                    ctx.getSource().sendFeedback(Component.literal("§a[5ifthNN] Server profile set to §b" + srv));
                                    return 1;
                                })))
                .then(ClientCommands.literal("map")
                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String map = StringArgumentType.getString(ctx, "name");
                                    FifthNNConfig cfg = FifthNNClient.getConfig();
                                    cfg.mapNameOverride = map;
                                    cfg.save();
                                    ctx.getSource().sendFeedback(Component.literal("§a[5ifthNN] Map tag set to §b" + map));
                                    return 1;
                                })))
                .then(ClientCommands.literal("pvp")
                        .then(ClientCommands.literal("1.8").executes(ctx -> {
                            FifthNNConfig cfg = FifthNNClient.getConfig();
                            cfg.pvpMode18 = true;
                            cfg.save();
                            ctx.getSource().sendFeedback(Component.literal("§a[5ifthNN] PvP mode set to §e1.8 BedWars"));
                            return 1;
                        }))
                        .then(ClientCommands.literal("modern").executes(ctx -> {
                            FifthNNConfig cfg = FifthNNClient.getConfig();
                            cfg.pvpMode18 = false;
                            cfg.save();
                            ctx.getSource().sendFeedback(Component.literal("§a[5ifthNN] PvP mode set to §7Modern"));
                            return 1;
                        })))
                .then(ClientCommands.literal("model")
                        .then(ClientCommands.literal("list").executes(ctx -> {
                            List<String> models = FifthNNClient.getAiController().listAvailableModels();
                            if (models.isEmpty()) {
                                ctx.getSource().sendFeedback(Component.literal(
                                        "§e[5ifthNN] No .onnx files in " + FifthNNClient.getConfig().getModelsDir()));
                            } else {
                                ctx.getSource().sendFeedback(Component.literal(
                                        "§b[5ifthNN] Available ONNX models: §f" + String.join(", ", models)));
                            }
                            return 1;
                        }))
                        .then(ClientCommands.literal("load")
                                .then(ClientCommands.argument("file", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String file = StringArgumentType.getString(ctx, "file");
                                            FifthNNClient.getAiController().loadSelectedModel(Minecraft.getInstance(), file);
                                            return 1;
                                        }))))
                .then(ClientCommands.literal("ai")
                        .then(ClientCommands.literal("on").executes(ctx -> {
                            FifthNNClient.getAiController().setMode(Minecraft.getInstance(), FifthNNSchema.AiControlMode.INFER);
                            return 1;
                        }))
                        .then(ClientCommands.literal("dagger").executes(ctx -> {
                            FifthNNClient.getAiController().setMode(Minecraft.getInstance(), FifthNNSchema.AiControlMode.HG_DAGGER);
                            return 1;
                        }))
                        .then(ClientCommands.literal("off").executes(ctx -> {
                            FifthNNClient.getAiController().emergencyStop(Minecraft.getInstance(), "Command off");
                            return 1;
                        })))
                .then(ClientCommands.literal("status").executes(ctx -> {
                    FifthNNConfig cfg = FifthNNClient.getConfig();
                    DatasetRecorderManager rec = FifthNNClient.getRecorder();
                    AiController ai = FifthNNClient.getAiController();
                    String s = String.format(
                            "§b[5ifthNN Status] §fRec=%s (%s, %d valid ticks) | Server=%s | PvP=%s | AI=%s (%s, %.2fms)",
                            rec.isRecording() ? "§aON" : "§7OFF",
                            cfg.getRecordingModeEnum().name(),
                            rec.getValidFramesWritten(),
                            cfg.serverProfile,
                            cfg.pvpMode18 ? "1.8" : "modern",
                            ai.getMode().name(),
                            ai.getEngine().getLoadedModelName(),
                            ai.getEngine().getEmaLatencyMs()
                    );
                    ctx.getSource().sendFeedback(Component.literal(s));
                    return 1;
                }));
    }

    private static void openGui() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.setScreen(new FifthNNControlScreen()));
    }
}
