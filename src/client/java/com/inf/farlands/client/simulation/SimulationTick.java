package com.inf.farlands.client.simulation;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Blocks;

public class SimulationTick {
    public static void tickSimulation(Minecraft minecraft) {
        Simulation simulation = ((SimulationHolder) minecraft).simulation();
        SimulationSection section = simulation.section((1L << 59) - 1,
                (1L << 59) - 1,
                (1L << 59) - 1);
        SimulationBlock block = SimulationBlock
                .full(Blocks.GRASS_BLOCK.defaultBlockState());
        section.clearAll();
        for (int x = 0; x < SimulationSection.SIZE; x++) {
            for (int z = 0; z < SimulationSection.SIZE; z++) {
                section.set(x, 0, z, block);
            }
        }
        simulation.commit();
    }
}
