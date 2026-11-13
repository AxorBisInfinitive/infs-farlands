package com.inf.farlands.client.gui;

import java.util.List;
import java.util.function.Function;

import com.inf.farlands.client.gui.page.DimensionTab;
import com.inf.farlands.client.gui.page.FamilySelectionStore;
import com.inf.farlands.simulation.registry.SimulationRegistries;
import com.inf.farlands.simulation.serialize.SimulationIO;
import com.inf.farlands.terrain.registry.FamilyKind;
import com.inf.farlands.terrain.registry.SystemId;
import com.inf.farlands.terrain.registry.SystemPageHooks;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 创建世界页的模拟之地页签：与边境之地同级，每个维度一页，每页只有地形一族。
 *
 * <p>
 * 内容与边境之地同源：选项、标题、默认项都经 {@link DimensionTab} 那一份，族行的校验链也同一条，所以参数
 * 改坏会照常标红。区别是选项来自模拟层的注册表，选择落在 {@link CreatingWorldSimulationConfig}，并被编成
 * {@code simulation.dat}。
 *
 * <p>
 * 进页时触发 {@link SystemPageHooks}：seed 的空框随机值按进页重抽，与边境之地同一条会话边界。
 */
public final class SimulationLandsTab extends DimensionTab {

    private static final String TITLE_KEY = "createWorld.tab.infs-farlands.simulationlands.title";

    public SimulationLandsTab(List<Identifier> dimensions) {
        super(dimensions);
    }

    @Override
    protected void onInit(List<Identifier> dimensions) {
        CreatingWorldSimulationConfig.setDimensions(dimensions);
    }

    @Override
    protected Component tabTitle() {
        return Component.translatable(TITLE_KEY);
    }

    @Override
    protected List<FamilyKind> families() {
        return CreatingWorldSimulationConfig.families();
    }

    @Override
    protected FamilySelectionStore store() {
        return CreatingWorldSimulationConfig.STORE;
    }

    @Override
    protected List<SystemId> ids(FamilyKind kind) {
        return SimulationRegistries.ids();
    }

    @Override
    protected Function<SystemId, Class<?>> classOf(FamilyKind kind) {
        return SimulationRegistries.classLookup();
    }

    @Override
    protected SystemId defaultId(Identifier page, FamilyKind kind) {
        return SimulationIO.defaultId();
    }

    @Override
    protected void onPageEntered(Identifier dimension) {
        SystemPageHooks.fireEntered(dimension);
    }
}
