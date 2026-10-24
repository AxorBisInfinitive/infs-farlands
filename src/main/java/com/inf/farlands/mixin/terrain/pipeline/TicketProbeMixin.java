package com.inf.farlands.mixin.terrain.pipeline;

import com.inf.farlands.debug.TicketFlowProbe;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 诊断：票层四个入口的流量。
 *
 * <p>四个入口分两组。带半径那两个是本模组自己的票，等级由 {@code 33 - radius} 定死；收 {@code Ticket}
 * 的那两个是玩家票与其它 vanilla 票的唯一去处，上一轮探针漏了它们，所以 33 与 32 那两组成对抖动一直
 * 没有归属。
 *
 * <p>不逐条打整场：量级与作业数同阶，逐条会把日志推到几百兆。逐条只留前若干条，其余进计数，由
 * {@link TicketFlowProbe} 周期汇总。只在 {@code logScheduler} 打开时输出，取完读数与同批探针一并删。
 */
@Mixin(TicketStorage.class)
public class TicketProbeMixin {

    @Inject(method = "addTicketWithRadius(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;I)V", at = @At("HEAD"))
    private void farlands$logTicketAddWithRadius(TicketType type, ChunkPos chunkPos, int radius, CallbackInfo ci) {
        farlands$logRadius("add", type, chunkPos, radius);
    }

    @Inject(method = "removeTicketWithRadius(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;I)V", at = @At("HEAD"))
    private void farlands$logTicketRemoveWithRadius(TicketType type, ChunkPos chunkPos, int radius, CallbackInfo ci) {
        farlands$logRadius("remove", type, chunkPos, radius);
    }

    @Inject(method = "addTicket(Lnet/minecraft/server/level/Ticket;Lnet/minecraft/world/level/ChunkPos;)V", at = @At("HEAD"))
    private void farlands$logTicketAdd(Ticket ticket, ChunkPos chunkPos, CallbackInfo ci) {
        farlands$logTicket("add", ticket, chunkPos);
    }

    @Inject(method = "removeTicket(Lnet/minecraft/server/level/Ticket;Lnet/minecraft/world/level/ChunkPos;)V", at = @At("HEAD"))
    private void farlands$logTicketRemove(Ticket ticket, ChunkPos chunkPos, CallbackInfo ci) {
        farlands$logTicket("remove", ticket, chunkPos);
    }

    /** 带半径入口：等级按原式现算一遍，与 {@code TicketStorage:139} 一致。 */
    private static void farlands$logRadius(String action, TicketType type, ChunkPos chunkPos, int radius) {
        int level = ChunkLevel.byStatus(FullChunkStatus.FULL) - radius;
        farlands$emit(action, type, radius, level, chunkPos);
    }

    /** 收 Ticket 入口：票自带类型与等级。 */
    private static void farlands$logTicket(String action, Ticket ticket, ChunkPos chunkPos) {
        farlands$emit(action, ticket.getType(), -1, ticket.getTicketLevel(), chunkPos);
    }

    private static void farlands$emit(String action, TicketType type, int radius, int level, ChunkPos chunkPos) {
        var key = BuiltInRegistries.TICKET_TYPE.getKey(type);
        TicketFlowProbe.ticket("add".equals(action), String.valueOf(key), radius, level, chunkPos.pack());
    }
}
